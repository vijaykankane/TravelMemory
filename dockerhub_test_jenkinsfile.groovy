pipeline {
    agent any
    
    environment {
        DOCKER_HUB_CREDENTIALS = 'dockerhub-credentials' // Jenkins credential ID
        DOCKER_HUB_REPO = 'your-dockerhub-username/test-repo'
        TEST_IMAGE_TAG = "${BUILD_NUMBER}-${env.GIT_COMMIT?.take(8) ?: 'latest'}"
    }
    
    options {
        timeout(time: 10, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }
    
    stages {
        stage('Docker Environment Check') {
            steps {
                script {
                    sh '''
                        echo "=== Docker Environment Information ==="
                        docker --version
                        docker info --format "{{json .}}" | jq '.ServerVersion, .Architecture, .OSType'
                        echo "=== Disk Space Check ==="
                        df -h /var/lib/docker || df -h /
                    '''
                }
            }
        }
        
        stage('Docker Hub Authentication Test') {
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: env.DOCKER_HUB_CREDENTIALS,
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh '''
                            echo "=== Testing Docker Hub Authentication ==="
                            echo "$DOCKER_PASS" | docker login --username "$DOCKER_USER" --password-stdin
                            echo "✅ Docker Hub login successful"
                        '''
                    }
                }
            }
        }
        
        stage('Docker Hub Pull Test') {
            steps {
                script {
                    sh '''
                        echo "=== Testing Docker Hub Pull Operations ==="
                        docker pull alpine:latest
                        docker pull nginx:alpine
                        echo "✅ Docker Hub pull operations successful"
                        
                        echo "=== Image Information ==="
                        docker images --format "table {{.Repository}}\\t{{.Tag}}\\t{{.Size}}\\t{{.CreatedAt}}" | head -5
                    '''
                }
            }
        }
        
        stage('Docker Hub Push Test') {
            steps {
                script {
                    sh '''
                        echo "=== Creating Test Image for Push ==="
                        cat > Dockerfile.test << 'EOF'
FROM alpine:latest
RUN echo "Jenkins Docker Hub connectivity test - Build: ${BUILD_NUMBER}" > /test-file
RUN echo "Timestamp: $(date)" >> /test-file
RUN echo "Node: ${NODE_NAME}" >> /test-file
CMD ["cat", "/test-file"]
EOF
                        
                        echo "=== Building Test Image ==="
                        docker build -f Dockerfile.test -t ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG} .
                        docker build -f Dockerfile.test -t ${DOCKER_HUB_REPO}:latest .
                        
                        echo "=== Testing Docker Hub Push Operations ==="
                        docker push ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG}
                        docker push ${DOCKER_HUB_REPO}:latest
                        echo "✅ Docker Hub push operations successful"
                        
                        echo "=== Verifying Pushed Image ==="
                        docker rmi ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG} || true
                        docker pull ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG}
                        docker run --rm ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG}
                        echo "✅ Image verification successful"
                    '''
                }
            }
        }
        
        stage('Registry API Test') {
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: env.DOCKER_HUB_CREDENTIALS,
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh '''
                            echo "=== Testing Docker Hub Registry API ==="
                            
                            # Get auth token
                            TOKEN=$(curl -s -H "Content-Type: application/json" \
                                -X POST \
                                -d "{\"username\": \"$DOCKER_USER\", \"password\": \"$DOCKER_PASS\"}" \
                                https://hub.docker.com/v2/users/login/ | jq -r .token)
                            
                            # Test repository access
                            REPO_NAME=$(echo ${DOCKER_HUB_REPO} | cut -d'/' -f2)
                            curl -s -H "Authorization: JWT $TOKEN" \
                                "https://hub.docker.com/v2/repositories/$DOCKER_USER/$REPO_NAME/" | jq '.name, .status'
                                
                            # List tags
                            curl -s -H "Authorization: JWT $TOKEN" \
                                "https://hub.docker.com/v2/repositories/$DOCKER_USER/$REPO_NAME/tags/?page_size=10" \
                                | jq '.results[].name' | head -5
                                
                            echo "✅ Docker Hub API access successful"
                        '''
                    }
                }
            }
        }
        
        stage('Network Connectivity Test') {
            steps {
                script {
                    sh '''
                        echo "=== Network Connectivity Tests ==="
                        
                        echo "Testing DNS resolution:"
                        nslookup hub.docker.com || dig hub.docker.com
                        
                        echo "Testing HTTPS connectivity:"
                        curl -I https://hub.docker.com/ --connect-timeout 10
                        
                        echo "Testing Docker Registry endpoints:"
                        curl -I https://registry-1.docker.io/v2/ --connect-timeout 10
                        curl -I https://auth.docker.io/token --connect-timeout 10
                        
                        echo "✅ Network connectivity tests passed"
                    '''
                }
            }
        }
    }
    
    post {
        always {
            script {
                sh '''
                    echo "=== Cleanup Operations ==="
                    docker logout || true
                    
                    # Cleanup test images
                    docker rmi ${DOCKER_HUB_REPO}:${TEST_IMAGE_TAG} || true
                    docker rmi ${DOCKER_HUB_REPO}:latest || true
                    docker rmi alpine:latest || true
                    docker rmi nginx:alpine || true
                    
                    # Cleanup build artifacts
                    rm -f Dockerfile.test || true
                    
                    # System cleanup
                    docker system prune -f || true
                    
                    echo "=== Final System State ==="
                    docker images
                    docker ps -a
                '''
            }
        }
        
        success {
            echo """
            ✅ ========================================
            ✅ DOCKER HUB CONNECTIVITY TEST PASSED
            ✅ ========================================
            
            All Docker Hub operations completed successfully:
            • Authentication: ✅
            • Image Pull: ✅  
            • Image Push: ✅
            • Registry API: ✅
            • Network Connectivity: ✅
            
            Docker Hub Repository: ${DOCKER_HUB_REPO}
            Test Image Tag: ${TEST_IMAGE_TAG}
            Jenkins Build: ${BUILD_NUMBER}
            """
        }
        
        failure {
            echo """
            ❌ ========================================
            ❌ DOCKER HUB CONNECTIVITY TEST FAILED
            ❌ ========================================
            
            Check the console output above for specific failure details.
            Common issues:
            • Invalid Docker Hub credentials
            • Network connectivity problems
            • Docker daemon issues
            • Repository permissions
            • Rate limiting
            """
        }
    }