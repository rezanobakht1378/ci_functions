def call(dockerRepoName, imageName, portNum, app_name)
{
  pipeline {
    agent { label 'python_agent' }
    environment {
      // Define the virtual environment's bin directory path
      VIRTUAL_ENV = "${WORKSPACE}/venv"
      PATH = "${VIRTUAL_ENV}/bin:${env.PATH}"
    }
          parameters {
              booleanParam(defaultValue: false, description: 'Deploy the App', name:'DEPLOY')
          }
          
          stages {
          stage('Setup') {
              steps {
                  script {
                      // Display the location of the workspace
                      echo "Running ${env.BUILD_ID} with workspace ${env.WORKSPACE}"
                      // Cleanup test-reports, api-test-reports, and venv directories if they exist
                      echo 'Cleaning up test-reports'
                      sh 'rm -rf test-reports || true'
      
                      echo 'Cleaning up api-test-reports'
                      sh 'rm -rf api-test-reports || true'
      
                      echo 'Removing existing virtual environment (venv)'
                      sh 'rm -rf venv || true'
      
                      // Create the virtual environment
                      sh 'python3 --version || echo "Python 3 not found"'
                      sh 'python3 -m venv venv'
  
                      // Install coverage in the virtual environment
                      sh 'venv/bin/pip install coverage'
      
                      // Install pylint in the virtual environment
                      sh 'venv/bin/pip install pylint pylint-django'
                      sh 'venv/bin/pip install pytest pytest-django pytest-cov'

                      // sh 'venv/bin/pip install pysonar'
                      // Install dependencies
                      sh 'venv/bin/pip install -r requirements.txt'
                      sh 'cp .env_example .env || true'

                      sh '''
                      docker run -d \
                        --name test-postgres \
                        --network cicd_jenkins-network \
                        -e POSTGRES_USER=user \
                        -e POSTGRES_PASSWORD=password \
                        -e POSTGRES_DB=db \
                        -p 5432:5432 \
                        postgres:17.4 || true

                      until docker exec test-postgres pg_isready -U user -d db; do
                        echo "Waiting for Postgres to be ready..."
                        sleep 2
                      done
                      echo "Postgres is ready!"
                      '''
                      sleep 10
                  }
              }
          }
          stage('Build') {
              steps {
                  script {
                      echo 'Running Build Stages...'
                      // Additional build steps if needed
                      sh 'venv/bin/python manage.py check'
                  }
              }
          }
          stage('Python Lint') {
              steps {
                  script {
                      echo 'Running Pylint on all Python files...'
                      sh 'venv/bin/pylint --fail-under=8 .'
                  }
              }
          }
          stage('Test and Coverage') {
              steps {
                script {
                  sh 'mkdir -p test-reports coverage-reports'
                  sh "venv/bin/pytest . --ds=${app_name}.test_settings --junitxml=test-reports/junit.xml --cov=. --cov-report=xml:coverage-reports/coverage.xml"
                }
              }
              post {
                  always {
                    sh 'docker rm -f test-postgres || true'
                    junit 'test-reports/junit.xml'
                    // publishCoverage adapters: [coberturaAdapter('coverage-reports/coverage.xml')]
                  }
              }
          }
          stage ("SonarQube Code Analyzer"){
            steps {
              script {
                      echo 'Running Code Analyzer Stages...'
                      withSonarQubeEnv('SonarQube'){
                        sh """
                        sonar-scanner -Dsonar.projectKey=${dockerRepoName} -Dsonar.sources=. -Dsonar.tests=**/tests -Dsonar.test.inclusions=**/*_test.py,**/test_*.py -Dsonar.exclusions=**/migrations/** -Dsonar.host.url=https://sonarqube.meshkatgames.ir/sonarqube -Dsonar.token=squ_30c80d59a26a40b32d3c9074790bb577ba41deda -Dsonar.python.coverage.reportPaths=coverage-reports/coverage.xml
                        """
                      }
                  }
            }
          }
          stage ("Quality Gate"){
            steps {
              timeout (time: 1, unit: 'HOURS') {
                waitForQualityGate abortPipeline: true
              }
            }
          }
          
          stage('Package') {
              when {
                  expression {
                      return (env.GIT_BRANCH == 'origin/main')
                  }
              }
              steps {
                  script {
                      echo "Must Build Docker Image and push to docker registery!!!"
                      // Docker build and push (uncomment when ready)
                      
                      withCredentials([string(credentialsId: 'DockerHub', variable: 'TOKEN')]) {
                          //sh "docker login -u 'reza_nobakht' -p '$TOKEN' docker.roshanjoo.ir" //docker not found
                          sh "echo \$TOKEN | docker login -u reza_nobakht --password-stdin docker.roshanjoo.ir"
                          //sh "docker build -t docker.roshanjoo.ir/bashir/${dockerRepoName.toLowerCase()}:latest --tag docker.roshanjoo.ir/bashir/${dockerRepoName.toLowerCase()}:${imageName} ."
                          sh "docker build -t docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:latest -t docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:1.0.0 ."
                          //sh "docker push docker.roshanjoo.ir/bashir/${dockerRepoName.toLowerCase()}:${imageName}"
                          sh "docker push docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:latest"
                          sh "docker push docker.roshanjoo.ir/bashir/${imageName.toLowerCase()}:1.0.0"
                      }
                      
                  }
              }
          }
          
          stage('Zip Artifacts') {
              steps {
                  // Create a zip file with all necessary files
                  sh 'zip -r app.zip . -i "*.py" "requirements.txt" ".env" -x "venv/*"'
              }
              post {
                  always {
                      // Archive the zip artifact
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
                      sh "docker stop ${dockerRepoName} || true && docker rm ${dockerRepoName} || true"
                      sh "docker run -d -p ${portNum}:${portNum} --name ${dockerRepoName} nobakht/${dockerRepoName}:${imageName}"
                  }
              }
          }
      }
  }
}
