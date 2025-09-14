pipeline {
 agent { label 'vklinux' }
 
    
    environment {
        DOCKER_HUB_CREDENTIALS = 'vijay-docker' // Jenkins credential ID
        DOCKER_HUB_REPO = 'https://hub.docker.com/repository/docker/vijaykankane/b2bsaas/general'
        TEST_IMAGE_TAG = "${BUILD_NUMBER}-test"
    }
    
    options {
        timeout(time: 10, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }
    
    stages {
        stage('Docker Environment Check') {
            steps {
                sh '''
                    echo "=== Docker Environment Check ==="
                    docker --version
                    docker info
                    df -h
                '''
            }
        }
        
        stage('Docker Hub Authentication') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: env.DOCKER_HUB_CREDENTIALS,
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh '''
                        echo "=== Testing Docker Hub Login ==="
                        echo "$DOCKER_PASS" | docker login --username "$DOCKER_USER" --password-stdin
                        echo "✅ Docker Hub authentication successful"
                    '''
                }
            }
        }
        
        stage('Docker Pull Test') {
            steps {
                sh '''
                    echo "=== Testing Docker Hub Pull ==="
                    docker pull alpine:latest
                    echo "✅ Docker Hub pull successful"
                '''
            }
        }
        
        stage('Docker Push Test') {
            steps {
                sh '''
                    echo "=== Creating and Pushing Test Image ==="
                    echo "FROM alpine:latest" > Dockerfile.test
                    echo "RUN echo 'Jenkins connectivity test - Build ${BUILD_NUMBER}' > /test.txt" >> Dockerfile.test
                    echo "CMD cat /test.txt" >> Dockerfile.test
                    
                    docker build -f Dockerfile.test -t ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG} .
                    docker push ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG}
                    echo "✅ Docker Hub push successful"
                '''
            }
        }
        
        stage('Verify Push') {
            steps {
                sh '''
                    echo "=== Verifying Pushed Image ==="
                    docker rmi ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG} || true
                    docker pull ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG}
                    docker run --rm ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG}
                    echo "✅ Image verification successful"
                '''
            }
        }
    }
    
    post {
        always {
            sh '''
                echo "=== Cleanup ==="
                docker logout || true
                docker rmi ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG} || true
                docker rmi alpine:latest || true
                rm -f Dockerfile.test || true
                docker system prune -f || true
            '''
        }
        
        success {
            echo '''
                ✅ Docker Hub connectivity test PASSED
                All operations completed successfully:
                • Authentication ✅
                • Pull operations ✅  
                • Push operations ✅
                • Image verification ✅
            '''
        }
        
        failure {
            echo '''
                ❌ Docker Hub connectivity test FAILED
                Check console output for details
            '''
        }
    }
}
