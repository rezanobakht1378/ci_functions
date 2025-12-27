def call(dockerRepoName, imageName, portNum, app_name)
{
  pipeline {
    agent { label 'python_agent' }

    environment {
      NODE_ENV = 'production'
      PATH = "${env.WORKSPACE}/node_modules/.bin:${env.PATH}"
    }

    parameters {
      booleanParam(defaultValue: false, description: 'Deploy the App', name: 'DEPLOY')
    }

    stages {

      stage('Setup') {
        steps {
          script {
            echo "Running ${env.BUILD_ID} with workspace ${env.WORKSPACE}"

            sh '''
              rm -rf node_modules .next coverage test-reports || true
              node --version || exit 1
              npm --version || exit 1
            '''

            sh 'npm ci'
            sh 'cp .env.example .env || true'
          }
        }
      }

      stage('Lint') {
        steps {
          script {
            echo 'Running ESLint...'
            sh 'npm run lint'
          }
        }
      }

      stage('Test') {
        steps {
          script {
            echo 'Running Tests...'
            sh '''
              mkdir -p test-reports
              npm run test -- --ci --reporters=default --reporters=jest-junit
            '''
          }
        }
        post {
          always {
            junit 'test-reports/junit.xml'
          }
        }
      }

      stage('Build') {
        steps {
          script {
            echo 'Building Next.js app...'
            sh 'npm run build'
          }
        }
      }

      stage('SonarQube Code Analyzer') {
        steps {
          script {
            withSonarQubeEnv('SonarQube') {
              sh """
                sonar-scanner \
                  -Dsonar.projectKey=${dockerRepoName} \
                  -Dsonar.sources=. \
                  -Dsonar.exclusions=node_modules/**,.next/**,coverage/** \
                  -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info \
                  -Dsonar.host.url=https://sonarqube.meshkatgames.ir/sonarqube \
                  -Dsonar.token=squ_30c80d59a26a40b32d3c9074790bb577ba41deda
              """
            }
          }
        }
      }

      stage('Quality Gate') {
        steps {
          timeout(time: 1, unit: 'HOURS') {
            waitForQualityGate abortPipeline: true
          }
        }
      }

      stage('Package') {
        when {
          expression {
            return (env.GIT_BRANCH == 'origin/main' && params.DEPLOY)
          }
        }
        steps {
          script {
            echo 'Building and pushing Docker image...'

            withCredentials([string(credentialsId: 'DockerHub', variable: 'TOKEN')]) {
              sh "echo \$TOKEN | docker login -u reza_nobakht --password-stdin docker.roshanjoo.ir"

              sh """
                docker build \
                  -t docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:latest \
                  -t docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:1.0.0 .
              """

              sh "docker push docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:latest"
              sh "docker push docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:1.0.0"
            }
          }
        }
      }

      stage('Zip Artifacts') {
        steps {
          sh '''
            zip -r app.zip . \
              -x node_modules/** \
              -x .next/** \
              -x .git/**
          '''
        }
        post {
          always {
            archiveArtifacts artifacts: 'app.zip', allowEmptyArchive: true
          }
        }
      }

      stage('Deliver') {
        when {
          expression { params.DEPLOY }
        }
        steps {
          script {
            sh """
              docker stop ${dockerRepoName} || true
              docker rm ${dockerRepoName} || true

              docker run -d \
                -p ${portNum}:3000 \
                --name ${dockerRepoName} \
                docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:latest
            """
          }
        }
      }
    }
  }
}
