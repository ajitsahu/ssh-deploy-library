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
                    remoteGroups[it] = yaml.remotes."$it"
                }

                def commandGroups = [:]
                it.command_groups.each {
                    commandGroups[it] = yaml.commands."$it"
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
