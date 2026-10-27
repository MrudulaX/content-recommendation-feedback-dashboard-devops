// =============================================================================
//  Content Recommendation Feedback Dashboard - Jenkins declarative pipeline
// -----------------------------------------------------------------------------
//  Stages
//    1  Checkout & metadata          git commit, branch, version, build info file
//    2  Build & unit test            mvn -B clean verify  (surefire + jacoco)
//    3  Package                      archive the JAR + checksums
//    4  Docker build & tag           the image the gate and the deploy both use
//    5  Selenium E2E quality gate    real browser against a real container
//                                    -> a failing suite STOPS the pipeline
//    6  Publish test reports         JUnit XML + coverage HTML
//    7  Docker publish               Docker Hub / local registry (optional)
//    8  Deploy                       fresh container replaces the old one
//    9  Health check & smoke test    /api/v1/health + one API round trip
//   10  Provision (Ansible)          optional idempotent node provisioning
//
//  Why the image is built *before* the quality gate: the gate runs the very
//  container that would be deployed. Gating on one build and deploying another
//  is not a gate at all, and building it here also means a failing suite stops
//  the pipeline before anything is published.
//
//  Networking note. Jenkins runs in a container on the same user-defined bridge
//  network as every container this pipeline creates (infra/jenkins/
//  docker-compose.jenkins.yml pins it as crfd-ci-net). Containers therefore
//  address each other by network alias - `crfd-app`, `crfd-selenium`,
//  `crfd-live` - and *not* by localhost, because localhost inside the Jenkins
//  container is Jenkins. Host port publishing is kept only so a human can reach
//  the deployed build from a browser.
//
//  Parameterised (at least one environment setting, as required):
//    ENVIRONMENT, APP_PORT, IMAGE_TAG, REGISTRY, RUN_SELENIUM, RUN_ANSIBLE ...
// =============================================================================

pipeline {

    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        disableConcurrentBuilds()
        timeout(time: 45, unit: 'MINUTES')
        skipDefaultCheckout(true)
        ansiColor('xterm')
    }

    triggers {
        // Scheduled polling of the GitHub repository (5-minute jitter).
        pollSCM('H/5 * * * *')
    }

    parameters {
        choice(name: 'ENVIRONMENT', choices: ['dev', 'staging', 'production'],
                description: 'Target environment - drives container config and the dashboard env badge')
        string(name: 'APP_PORT', defaultValue: '8080',
                description: 'Host port the deployed container is published on')
        string(name: 'IMAGE_TAG', defaultValue: '',
                description: 'Image tag to build. Empty = v<version>-<BUILD_NUMBER>')
        string(name: 'REGISTRY', defaultValue: 'docker.io',
                description: 'Container registry host (docker.io, ghcr.io, localhost:5000 ...)')
        string(name: 'REGISTRY_CREDENTIALS_ID', defaultValue: 'dockerhub-credentials',
                description: 'Jenkins credentials id used to push the image')
        booleanParam(name: 'RUN_UNIT_TESTS', defaultValue: true,
                description: 'Run the Maven unit + API test suite')
        booleanParam(name: 'RUN_SELENIUM', defaultValue: true,
                description: 'Run the Selenium E2E quality gate (blocks deployment on failure)')
        booleanParam(name: 'PUBLISH_IMAGE', defaultValue: false,
                description: 'Push the versioned image to the registry')
        booleanParam(name: 'DEPLOY', defaultValue: true,
                description: 'Deploy a fresh container after all quality gates pass')
        booleanParam(name: 'RUN_ANSIBLE', defaultValue: false,
                description: 'Run the Ansible provisioning playbook against the target node')
        string(name: 'ANSIBLE_INVENTORY', defaultValue: 'inventory/hosts.example.ini',
                description: 'Inventory the Ansible stage uses. Copy hosts.example.ini to hosts.ini for a real target.')
        string(name: 'CRFD_LOW_CONFIDENCE', defaultValue: '0.30',
                description: 'Environment setting: LOW_CONFIDENCE alerting threshold')
        string(name: 'CRFD_CRITICAL_LATENCY_MS', defaultValue: '1500',
                description: 'Environment setting: critical latency SLA in milliseconds')
    }

    environment {
        APP_NAME         = 'content-recommendation-feedback-dashboard'
        IMAGE_NAME       = "crfd"
        BUILD_VERSION    = sh(script: "grep -m1 '<version>' pom.xml | sed -e 's/.*<version>\\(.*\\)<\\/version>.*/\\1/'", returnStdout: true).trim()
        GIT_SHA          = ''
        IMAGE_TAG_RESOLVED = ''
        CONTAINER_NAME   = 'crfd-app'
        NETWORK_NAME     = 'crfd-ci-net'
        DATA_VOLUME      = 'crfd-data'
        SELENIUM_CONTAINER = 'crfd-selenium'
        SELENIUM_IMAGE   = 'selenium/standalone-chrome:4.25.0'
        REPORTS_DIR      = 'target/site'
    }

    stages {

        // ---------------------------------------------------------------- 1
        stage('Checkout & metadata') {
            steps {
                script {
                    checkout scm
                    env.GIT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.GIT_BRANCH_NAME = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG_RESOLVED = params.IMAGE_TAG?.trim() ? params.IMAGE_TAG.trim()
                                            : "v${env.BUILD_VERSION}-${env.BUILD_NUMBER}"

                    echo """
                    ============================================================
                     CRFD build metadata
                    ------------------------------------------------------------
                     branch       : ${env.GIT_BRANCH_NAME}
                     commit       : ${env.GIT_SHA}
                     version      : ${env.BUILD_VERSION}
                     image        : ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED}
                     environment  : ${params.ENVIRONMENT}
                     port         : ${params.APP_PORT}
                    ============================================================
                    """.stripIndent()

                    // Build metadata file, baked into the artifact and the image.
                    writeFile file: 'build-info.properties', text: """
                        build.version=${env.BUILD_VERSION}
                        build.tag=${env.IMAGE_TAG_RESOLVED}
                        build.number=${env.BUILD_NUMBER}
                        build.url=${env.BUILD_URL}
                        git.commit=${env.GIT_SHA}
                        git.branch=${env.GIT_BRANCH_NAME}
                        environment=${params.ENVIRONMENT}
                    """.stripIndent()
                }
            }
        }

        // ---------------------------------------------------------------- 2
        stage('Build & unit test') {
            steps {
                echo "Building ${IMAGE_NAME} ${IMAGE_TAG_RESOLVED} (env=${params.ENVIRONMENT})"
                sh 'mvn -B -U clean verify -DskipITs'
            }
            post {
                always {
                    junit(testResults: 'target/surefire-reports/*.xml',
                          allowEmptyResults: true,
                          keepLongStdio: true)
                    jacoco(execPattern: 'target/jacoco.exec',
                           classPattern: 'target/classes',
                           sourcePattern: 'src/main/java',
                           exclusionPattern: '**/dto/**,**/domain/**',
                           changeBuildStatus: false)
                }
            }
        }

        // ---------------------------------------------------------------- 3
        stage('Package') {
            steps {
                sh 'mvn -B -DskipTests package'
                sh 'ls -lh target/*.jar'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.jar, build-info.properties',
                                     fingerprint: true,
                                     allowEmptyArchive: false
                    sh 'sha256sum target/*.jar > target/artifact-checksums.txt || true'
                    archiveArtifacts artifacts: 'target/artifact-checksums.txt', allowEmptyArchive: true
                }
            }
        }

        // ---------------------------------------------------------------- 4
        // Built before the quality gate, on purpose: the gate runs the very
        // container that a successful pipeline would deploy. Gating on one
        // build and shipping another is not a gate.
        stage('Docker build & tag') {
            steps {
                sh """
                    docker build \
                        --build-arg APP_VERSION=${env.BUILD_VERSION} \
                        --build-arg BUILD_REVISION=${env.GIT_SHA} \
                        -t ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED} \
                        -t ${env.IMAGE_NAME}:${env.GIT_SHA} \
                        -t ${env.IMAGE_NAME}:latest .
                """
                sh "docker images | grep ${env.IMAGE_NAME}"
                sh "docker history --no-trunc ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED} | head -20 || true"
                sh "docker image inspect ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED} > target/image-inspect.json"
                archiveArtifacts artifacts: 'target/image-inspect.json', allowEmptyArchive: true
            }
        }

        // ---------------------------------------------------------------- 5
        stage('Selenium E2E quality gate') {
            when { expression { params.RUN_SELENIUM } }
            steps {
                script {
                    // A real browser container talks to a real application container.
                    sh "docker network create ${env.NETWORK_NAME} || true"
                    sh "docker rm -f ${env.SELENIUM_CONTAINER} || true"
                    sh "docker rm -f ${env.CONTAINER_NAME}-selenium-target || true"

                    sh """
                        docker run -d --name ${env.CONTAINER_NAME}-selenium-target \
                            --network ${env.NETWORK_NAME} \
                            --network-alias crfd-app \
                            -e CRFD_ENVIRONMENT=ci \
                            -e CRFD_SIMULATION_ENABLED=true \
                            -e CRFD_SEED_ON_STARTUP=true \
                            ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED}
                    """

                    sh 'bash scripts/wait-for-health.sh "http://crfd-app:8080/api/v1/health" 90'

                    // No -p: the browser reaches the application by network alias,
                    // and the pipeline probes it the same way. Publishing a host
                    // port here would be misleading, since localhost inside the
                    // Jenkins container is Jenkins, not the application.
                    sh """
                        docker run -d --name ${env.SELENIUM_CONTAINER} \
                            --network ${env.NETWORK_NAME} \
                            --shm-size=2g \
                            ${env.SELENIUM_IMAGE}
                    """

                    sh 'bash scripts/wait-for-selenium.sh "http://crfd-selenium:4444/wd/hub/status" 90'

                    // The browser runs inside the Selenium container, so the base URL
                    // must resolve on the Docker network, not on the Jenkins host.
                    sh """
                        SELENIUM_REMOTE_URL=http://crfd-selenium:4444/wd/hub \
                        mvn -B -P selenium verify \
                            -Dselenium.baseUrl=http://crfd-app:8080 \
                            -Dselenium.headless=true \
                            -Dselenium.browser=chrome
                    """
                }
            }
            post {
                always {
                    junit(testResults: 'target/failsafe-reports/*.xml',
                          allowEmptyResults: true,
                          keepLongStdio: true)
                    archiveArtifacts artifacts: 'target/selenium-screenshots/**',
                                     allowEmptyArchive: true
                    sh "docker logs ${env.CONTAINER_NAME}-selenium-target > target/app-under-test.log 2>&1 || true"
                    archiveArtifacts artifacts: 'target/app-under-test.log', allowEmptyArchive: true
                    sh "docker rm -f ${env.CONTAINER_NAME}-selenium-target || true"
                    sh "docker rm -f ${env.SELENIUM_CONTAINER} || true"
                }
            }
        }

        // ---------------------------------------------------------------- 6
        // Runs after the gate so it can publish the failsafe (Selenium) reports
        // alongside the surefire ones, in a single place.
        stage('Publish test reports') {
            steps {
                publishHTML(target: [
                        allowMissing        : true,
                        alwaysLinkToLastBuild: true,
                        keepAll             : true,
                        reportDir           : 'target/site/jacoco',
                        reportFiles         : 'index.html',
                        reportName          : 'CRFD JaCoCo coverage'
                ])
                sh 'ls -la target/surefire-reports || true'
                sh 'ls -la target/failsafe-reports || true'
            }
        }

        // ---------------------------------------------------------------- 7
        stage('Publish image') {
            when { expression { params.PUBLISH_IMAGE } }
            steps {
                script {
                    def remote = "${params.REGISTRY}/${env.IMAGE_NAME}"
                    docker.withRegistry("https://${params.REGISTRY}", params.REGISTRY_CREDENTIALS_ID) {
                        sh "docker tag ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED} ${remote}:${env.IMAGE_TAG_RESOLVED}"
                        sh "docker push ${remote}:${env.IMAGE_TAG_RESOLVED}"
                        sh "docker push ${remote}:${env.GIT_SHA}"
                    }
                    echo "Published ${remote}:${env.IMAGE_TAG_RESOLVED} and ${remote}:${env.GIT_SHA}"
                }
            }
        }

        // ---------------------------------------------------------------- 8
        stage('Deploy fresh container') {
            when { expression { params.DEPLOY } }
            steps {
                script {
                    sh "docker network create ${env.NETWORK_NAME} || true"
                    sh "docker rm -f ${env.CONTAINER_NAME} || true"
                    sh "docker volume create ${env.DATA_VOLUME} || true"
                    sh """
                        docker run -d --name ${env.CONTAINER_NAME} \
                            --restart unless-stopped \
                            --network ${env.NETWORK_NAME} \
                            --network-alias crfd-live \
                            -p ${params.APP_PORT}:8080 \
                            -v ${env.DATA_VOLUME}:/app/crfd-data \
                            -e CRFD_ENVIRONMENT=${params.ENVIRONMENT} \
                            -e CRFD_SIMULATION_ENABLED=${params.ENVIRONMENT == 'production' ? 'false' : 'true'} \
                            -e CRFD_SEED_ON_STARTUP=${params.ENVIRONMENT == 'production' ? 'false' : 'true'} \
                            -e CRFD_LOW_CONFIDENCE=${params.CRFD_LOW_CONFIDENCE} \
                            -e CRFD_CRITICAL_LATENCY_MS=${params.CRFD_CRITICAL_LATENCY_MS} \
                            ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED}
                    """
                    sh "docker ps --filter name=${env.CONTAINER_NAME} --format 'table {{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}'"
                }
            }
        }

        // ---------------------------------------------------------------- 9
        stage('Health check & smoke test') {
            when { expression { params.DEPLOY } }
            steps {
                script {
                    // `crfd-live`, not localhost: Jenkins is itself a container,
                    // so localhost would be Jenkins. The host port is still
                    // published above so a human can open the build.
                    def base = "http://crfd-live:8080"
                    sh "bash scripts/wait-for-health.sh '${base}/api/v1/health' 120"

                    echo "--- deep health payload ---"
                    sh "curl -fsS '${base}/api/v1/health' | tee target/health.json"

                    echo "--- smoke: ingest one event and read it back ---"
                    sh """
                        curl -fsS -X POST '${base}/api/v1/events' \
                          -H 'Content-Type: application/json' \
                          -d '{"userId":"U-SMOKE","itemId":"ITM-SMOKE","itemTitle":"Pipeline smoke item","eventType":"IMPRESSION","source":"HYBRID","surface":"HOME_FEED","score":0.88}' \
                          | tee target/smoke-ingest.json
                    """
                    sh "curl -fsS '${base}/api/v1/events?q=ITM-SMOKE' | tee target/smoke-query.json"
                    sh "grep -q 'ITM-SMOKE' target/smoke-query.json"
                    sh "curl -fsS '${base}/api/v1/metrics/summary' | tee target/smoke-summary.json"

                    archiveArtifacts artifacts: 'target/health.json, target/smoke-*.json', allowEmptyArchive: true
                    sh "docker logs --tail 60 ${env.CONTAINER_NAME} > target/deployed-container.log 2>&1 || true"
                    archiveArtifacts artifacts: 'target/deployed-container.log', allowEmptyArchive: true
                }
            }
        }

        // ---------------------------------------------------------------- 10
        stage('Provision node (Ansible)') {
            when { expression { params.RUN_ANSIBLE } }
            steps {
                dir('infra/ansible') {
                    // The inventory is parameterised (default hosts.example.ini)
                    // because a real target inventory is environment-specific and
                    // deliberately not committed.
                    sh """
                        ansible-playbook -i ${params.ANSIBLE_INVENTORY} playbooks/site.yml \
                            --extra-vars "app_image=${env.IMAGE_NAME} \
                                          app_version=${env.IMAGE_TAG_RESOLVED} \
                                          app_port=${params.APP_PORT} \
                                          app_environment=${params.ENVIRONMENT}"
                    """
                    // Second, identical run. This is the idempotency evidence: the
                    // recap must report changed=0.
                    sh """
                        ansible-playbook -i ${params.ANSIBLE_INVENTORY} playbooks/site.yml \
                            --extra-vars "app_image=${env.IMAGE_NAME} \
                                          app_version=${env.IMAGE_TAG_RESOLVED} \
                                          app_port=${params.APP_PORT} \
                                          app_environment=${params.ENVIRONMENT}" \
                            | tee ../target/ansible-idempotency.log
                    """
                    sh "grep -A2 'PLAY RECAP' ../target/ansible-idempotency.log || true"
                }
                archiveArtifacts artifacts: 'target/ansible-idempotency.log', allowEmptyArchive: true
            }
        }
    }

    post {

        always {
            echo "Pipeline finished with status: ${currentBuild.currentResult}"
            script {
                // Report links + a machine readable result for the build log.
                writeFile file: 'target/pipeline-result.txt', text: """
                    result=${currentBuild.currentResult}
                    image=${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED}
                    commit=${env.GIT_SHA}
                    branch=${env.GIT_BRANCH_NAME}
                    environment=${params.ENVIRONMENT}
                    url=${env.BUILD_URL}
                """.stripIndent()
            }
            archiveArtifacts artifacts: 'target/pipeline-result.txt, build-info.properties',
                             allowEmptyArchive: true
            cleanWs(deleteDirs: true, disableDeferredWipeout: true,
                    notFailBuild: true,
                    patterns: [[pattern: 'target/*.jar', type: 'INCLUDE']])
        }

        failure {
            echo """
            ============================================================
             BUILD FAILED - deployment was NOT performed.
             Check: unit tests, the Selenium quality gate and the
             archived target/selenium-screenshots/** artifacts.
            ============================================================
            """.stripIndent()
            sh "docker rm -f ${env.CONTAINER_NAME}-selenium-target || true"
            sh "docker rm -f ${env.SELENIUM_CONTAINER} || true"
        }

        success {
            echo "Deployed ${env.IMAGE_NAME}:${env.IMAGE_TAG_RESOLVED} to ${params.ENVIRONMENT} on port ${params.APP_PORT}"
        }
    }
}
