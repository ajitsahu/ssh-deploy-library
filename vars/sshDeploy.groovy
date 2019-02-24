#!/usr/bin/env groovy

def call(String yamlName) {
    def yaml = readYaml file: yamlName
    withCredentials([usernamePassword(credentialsId: yaml.config.credentials_id, passwordVariable: 'password', usernameVariable: 'userName')]) {
        yaml.steps.each { stageName, step ->
            step.each {
                def remoteGroups = [:]
                def allRemotes = []
                it.remote_groups.each {
                    remoteGroups[it] = yaml.remote_groups."$it"
                }

                def commandGroups = [:]
                it.command_groups.each {
                    commandGroups[it] = yaml.command_groups."$it"
                }
                def isSudo = false
                remoteGroups.each { remoteGroupName, remotes ->
                    allRemotes += remotes.collect { remote ->
                        if(!remote.name)
                            remote.name = remote.host
                        remote.user = userName
                        remote.password = password
                        remote.allowAnyHosts = true
                        remote.groupName = remoteGroupName
                        remote
                    }
                }
                if(allRemotes) {
                    if(allRemotes.size() > 1) {
                        def stepsForParallel = allRemotes.collectEntries { remote ->
                            ["${remote.groupName}-${remote.name}" : transformIntoStep(stageName, remote.groupName, remote, commandGroups)]
                        }
                        stage(stageName) {
                            parallel stepsForParallel
                        }
                    } else {
                        def remote = allRemotes.first()
                        stage(stageName + "\n" + remote.groupName + "-" + remote.name) {
                            transformIntoStep(stageName, remote.groupName, remote, commandGroups).call()
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
            sshScript remote: remote, script: command
            break
        case "gets":
            sshGet remote: remote, from: command.from, into: command.into, override: command.override
            break
        case "puts":
            sshPut remote: remote, from: from, into: into
            break
        case "removes":
            sshRemove remote: remote, path: command
            break
        default:
            error "Invalid Command: ${stageName} -> ${remoteGroupName} -> ${commandGroupName} -> ${commandName}"
            break
    }
}
