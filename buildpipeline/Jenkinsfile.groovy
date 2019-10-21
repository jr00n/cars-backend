#!groovy
def mvnCmd = "mvn -B"
def appName = "cars-backend"
def version
def project = env.PROJECT_NAME

pipeline {
    agent none
    options {
		skipDefaultCheckout()
        disableConcurrentBuilds() 
	}
    stages {
        stage('Check out') {
            agent {
                label "jos-m3-openjdk8"
            }
            steps {
                checkout scm
                // stash workspace
				stash(name: 'ws', includes: '**', excludes: '**/.git/**')
            }
        }
        stage('Maven build') {
            agent {
                label "jos-m3-openjdk8"
            }
            steps {
                unstash 'ws'
				sh(script: "${mvnCmd} -DskipTests -Popenshift clean package" )
				stash name: 'jar', includes: 'target/**/*'
            }    
        }
        stage('Maven unit tests') {
            agent {
                label "jos-m3-openjdk8"
            }
            steps {
                unstash 'ws'
                sh(script: "${mvnCmd} test")
            }
            post {
				success {
					junit '**/surefire-reports/**/*.xml'
				}
            }
		}
        stage('Create Image Builder') {
            when {
                expression {
                    openshift.withCluster() {
                        return !openshift.selector("bc", "${appName}").exists();
                    }
                }
            }
            steps {
                script {
                    openshift.withCluster() {
                        openshift.newBuild("--name=${appName}", "--image-stream=openjdk-8-rhel8", "--binary=true")
                    }
                }
            }
        }
        stage('Build Image') {
            agent {
                label "jos-m3-openjdk8"
            }
            steps {
                unstash 'jar'
                script {
                    openshift.withCluster() {
                        openshift.selector("bc", appName).startBuild("--from-file=target/${appName}.jar", "--wait=true")
                    }
                }
            }
        }
        stage('Create deployment in O') {
            when {
                expression {
                    openshift.withCluster() {
                        return !openshift.selector("dc", "${appName}").exists()
                    }
                }
            }
            steps {
                script {
                    openshift.withCluster() {
                        def app = openshift.newApp("${appName}:latest")
                        app.narrow("svc").expose();
                        // geen triggers op redeploy wanneer het image veranderd. Jenkins is in control
                        openshift.set("triggers", "dc/os-fase2-jeeapp", "--manual")
                        openshift.set("probe dc/os-fase2-jeeapp --readiness --get-url=http://:8080/actuator/health --initial-delay-seconds=30 --failure-threshold=10 --period-seconds=10")
                        openshift.set("probe dc/os-fase2-jeeapp --liveness  --get-url=http://:8080/actuator/health --initial-delay-seconds=180 --failure-threshold=10 --period-seconds=10")
                        def dc = openshift.selector("dc", "${appName}")
                        

                        // wachten tot alle replicas beschikbaar zijn.
                        while (dc.object().spec.replicas != dc.object().status.availableReplicas) {
                            echo "Wait for all replicas are available"
                            sleep 10
                        }
                    }
                }
            }
        }
        stage('Deploy in O') {
            steps {
                script {
                    openshift.withCluster() {
                        def dc = openshift.selector("dc", "${appName}")
                        dc.rollout().latest();
                        // wachten tot alle replicas beschikbaar zijn.
                        while (dc.object().spec.replicas != dc.object().status.availableReplicas) {
                            echo "Wait for all replicas are available"
                            sleep 10
                        }
                    }
                }
            }
        }
    }
}
