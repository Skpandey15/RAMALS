pipeline {
  agent { label 'windows && ramals-dev' }

  options {
    disableConcurrentBuilds()
    skipDefaultCheckout(true)
    timeout(time: 90, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
  }

  triggers {
    // Local Jenkins is normally behind a home router, so polling is the dependable default.
    // A GitHub webhook may be added later without changing the deployment contract.
    pollSCM('H/2 * * * *')
  }

  stages {
    stage('Checkout trusted main') {
      steps {
        deleteDir()
        checkout([$class: 'GitSCM',
          branches: [[name: 'refs/heads/main']],
          userRemoteConfigs: [[url: 'https://github.com/Skpandey15/RAMALS.git']]])
      }
    }

    stage('Validate deployment source') {
      steps {
        bat label: 'Validate exact main commit', script: '''@echo off
pwsh -NoProfile -NonInteractive -File .\\deploy\\jenkins\\deploy-main.ps1 -ValidateOnly
'''
      }
    }

    stage('Approve local k3d DEV') {
      steps {
        timeout(time: 30, unit: 'MINUTES') {
          input message: 'Do you want to deploy to the local/dev k3d environment?',
            ok: 'Deploy', submitter: 'ramals-admin'
        }
      }
    }

    stage('Deploy local k3d DEV') {
      steps {
        bat label: 'Bootstrap and smoke-test RAMALS', script: '''@echo off
pwsh -NoProfile -NonInteractive -File .\\deploy\\jenkins\\deploy-main.ps1
'''
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'artifacts/jenkins/**', allowEmptyArchive: true,
        fingerprint: true
    }
  }
}
