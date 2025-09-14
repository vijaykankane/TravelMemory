pipeline {
    agent { label 'jenkins-agent' }
    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/aryanm12/TravelMemory.git'
            }
        }
        stage('Install') {
            steps {
                sh 'cd backend; npm install'
            }
        }
        stage('Build') {
            steps {
                sh 'cd backend ; npm run build'
            }
        }
    }

}





