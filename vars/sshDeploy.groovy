#!/usr/bin/env groovy

def call(String yamlName) {
    def yaml = readYaml file: yamlName
    withCredentials([usernamePassword(credentialsId: yaml.config.credentials_id, passwordVariable: 'password', usernameVariable: 'userName')]) {
        yaml.steps.each { stageName, step ->
            step.each {
                println "yaml ==> ${yaml}"
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
                        if(params.SSH_USER) {
                            remote.user = params.SSH_USER
                            remote.password = params.PASSWORD
                            isSudo = true
                        } else {
                            remote.user = userName
                            remote.password = password
                        }
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
                        stage(stageName + " \u2609 Size: ${allRemotes.size()}") {
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
