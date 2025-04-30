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
      
                      // Install dependencies
                      sh 'venv/bin/pip install -r requirements.txt'
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
                   sh '''
                      mkdir -p test-reports coverage-reports
                      venv/bin/pytest . \
                          --ds=${app_name}.test_settings \
                          --junitxml=test-reports/junit.xml \
                          --cov=. \
                          --cov-report=xml:coverage-reports/coverage.xml
                    '''
                }
              }
              post {
                  always {
                    junit 'test-reports/junit.xml'
                    // publishCoverage adapters: [coberturaAdapter('coverage-reports/coverage.xml')]
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
                      /*
                      withCredentials([string(credentialsId: 'DockerHub', variable: 'TOKEN')]) {
                          sh "docker login -u 'nobakht' -p '$TOKEN' docker.io"
                          sh "docker build -t ${dockerRepoName}:latest --tag nobakht/${dockerRepoName}:${imageName} ."
                          sh "docker push nobakht/${dockerRepoName}:${imageName}"
                      }
                      */
                  }
              }
          }
          
          stage('Zip Artifacts') {
              steps {
                  // Create a zip file with all necessary files
                  sh 'zip -r app.zip . -i "*.py" "requirements.txt" -x "venv/*"'
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
