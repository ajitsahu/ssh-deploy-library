#!/usr/bin/env groovy

def call(String yamlName) {
    def yaml = readYaml file: yamlName
    withCredentials([usernamePassword(credentialsId: yaml.config.credentials_id, passwordVariable: 'password', usernameVariable: 'userName')]) {
        
        if(!userName && params.SSH_USER) {
            error "userName is null or empty, please check credentials_id."
        }

        if(!password && params.PASSWORD) {
            error "password is null or empty, please check credentials_id."
        }
        
        yaml.steps.each { stageName, step ->
            step.each {
                def remoteGroups = [:]
                def allRemotes = []
                
                it.remote_groups.each {
                    if(!yaml.remote_groups."$it") {
                        error "remotes groups are empty/invalid for the given stage: ${stageName}, command group: ${it}. Please check yml."
                    }
                    remoteGroups[it] = yaml.remote_groups."$it"
                }

                def commandGroups = [:]
                it.command_groups.each {
                    if(!yaml.command_groups."$it") {
                        error "command groups are empty/invalid for the given stage: ${stageName}, command group: ${it}. Please check yml."
                    }
                    commandGroups[it] = yaml.command_groups."$it"
                }
                def isSudo = false
                remoteGroups.each { remoteGroupName, remotes ->
                    allRemotes += remotes.collect { remote ->
                        if(!remote.host) {
                            throw IllegalArgumentException("host missing for one of the nodes in ${remoteGroupName}")
                        }
                        if(!remote.name)
                            remote.name = remote.host
                            remote.user = params.SSH_USER
                            remote.password = params.PASSWORD
                            isSudo = true
                        remote.allowAnyHosts = true
                        remote.groupName = remoteGroupName
                        remote
                    }
                }
                if(allRemotes) {
                    if(allRemotes.size() > 1) {
                        def stepsForParallel = allRemotes.collectEntries { remote ->
                            ["${remote.groupName}-${remote.name}" : transformIntoStep(stageName, remote.groupName, remote, commandGroups, isSudo, yaml.config)]
                        }
                        stage(stageName + " \u2609 Size: ${allRemotes.size()}") {
                            parallel stepsForParallel
                        }
                    } else {
                        def remote = allRemotes.first()
                        stage(stageName + "\n" + remote.groupName + "-" + remote.name) {
                            transformIntoStep(stageName, remote.groupName, remote, commandGroups, isSudo, yaml.config).call()
                        }
                    }
                }
            }
        }
    }
}

private transformIntoStep(stageName, remoteGroupName, remote, commandGroups, isSudo, config) {
    return {
        def finalRetryResult = true
        commandGroups.each { commandGroupName, commands ->
            echo "Running ${commandGroupName} group of commands."
            commands.each { command ->
                command.each { commandName, commandList ->
                    commandList.each {
                        validateCommands(stageName, remoteGroupName, commandGroupName, commandName, it)
                        executeCommands(remote, stageName, remoteGroupName, commandGroupName, commandName, it, isSudo)
                            echo "DryRun Mode: Running ${commandName}."
                            echo "Remote: ${remote}"
                            echo "Command: ${it}"
                     }
                }
            }
        }
    }
}

private validateCommands(stageName, remoteGroupName, commandGroupName, commandName, command) {
    if(commandName in ["gets", "puts"]) {
        if(!command.from)
            error "${stageName} -> ${remoteGroupName} -> ${commandGroupName} -> ${commandName} -> from is empty or null."
        if(!command.into)
            error "${stageName} -> ${remoteGroupName} -> ${commandGroupName} -> ${commandName} -> into is empty or null."
    }
}

private executeCommands(remote, stageName, remoteGroupName, commandGroupName, commandName, command, isSudo) {
    switch (commandName) {
        case "commands":
            sshCommand remote: remote, command: command, sudo: isSudo
            break
        case "scripts":
            sshScript remote: remote, script: command, sudo: isSudo
            break
        case "gets":
            sshGet remote: remote, from: command.from, into: command.into, override: command.override
            break
        case "puts":
            sshPut remote: remote, from: command.from, into: command.into
            break
        case "removes":
            sshRemove remote: remote, path: command
            break
        default:
            error "Invalid Command: ${stageName} -> ${remoteGroupName} -> ${commandGroupName} -> ${commandName}"
            break
    }
}
