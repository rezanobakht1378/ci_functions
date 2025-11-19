def call()
{
  pipeline {
   agent { label 'unity-builder' }
  
   stages {
   stage('Checkout') {
   steps {
   git branch: 'main', url: 'YOUR_REPO_URL'
   }
   }
  
   stage('Build APK') {
   steps {
   bat """
   "C:\\Program Files\\Unity\\Hub\\Editor\\2022.3.0f1\\Editor\\Unity.exe" ^
   -batchmode -nographics -quit ^
   -projectPath "%WORKSPACE%" ^
   -executeMethod BuildSystem.BuildAndroid ^
   -logFile unity.log
   """
   }
   }
   }
  }
}
