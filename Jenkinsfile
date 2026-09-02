pipeline {
  // No global agent. A pipeline-level agent is held for the whole run, including the approval
  // stage, so a build parked on the input occupied the one Windows agent for up to half an hour --
  // and disableConcurrentBuilds() meant nothing else could use the label meanwhile. Each stage now
  // takes the agent only while it needs it.
  agent none

  options {
    disableConcurrentBuilds()
    skipDefaultCheckout(true)
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
  }

  triggers {
    // Local Jenkins is normally behind a home router, so polling is the dependable default.
    // A GitHub webhook may be added later without changing the deployment contract.
    pollSCM('H/2 * * * *')
  }

  stages {
    stage('Checkout trusted main') {
      agent { label 'windows && ramals-dev' }
      options { timeout(time: 20, unit: 'MINUTES') }
      steps {
        deleteDir()
        checkout([$class: 'GitSCM',
          branches: [[name: 'refs/heads/main']],
          userRemoteConfigs: [[url: 'https://github.com/Skpandey15/RAMALS.git']]])
      }
    }

    stage('Validate deployment source') {
      agent { label 'windows && ramals-dev' }
      options { timeout(time: 20, unit: 'MINUTES') }
      steps {
        bat label: 'Validate exact main commit', script: '''@echo off
pwsh -NoProfile -NonInteractive -File .\\deploy\\jenkins\\deploy-main.ps1 -ValidateOnly
'''
      }
    }

    stage('Approve local k3d DEV') {
      // Deliberately no agent: waiting on a person must not occupy the build machine.
      options { timeout(time: 30, unit: 'MINUTES') }
      steps {
        script {
          // submitterParameter carries the approver's username through to the evidence bundle.
          // summary.json recorded the build but never who authorised it, which is the one fact a
          // human gate exists to establish -- and the only one not reconstructable afterwards.
          env.RAMALS_APPROVER = input(
            message: 'Do you want to deploy to the local/dev k3d environment?',
            ok: 'Deploy', submitter: 'ramals-admin', submitterParameter: 'approver')
        }
      }
    }

    stage('Deploy local k3d DEV') {
      agent { label 'windows && ramals-dev' }
      // The deploy gets its own budget. One pipeline-level timeout previously spanned the approval
      // wait as well, so a late approval silently shortened the deployment -- and an abort part-way
      // through leaves images pushed and workloads half-rolled, which is the worst outcome of the
      // three (clean failure, clean success, or a cluster nobody has a clear picture of).
      options { timeout(time: 90, unit: 'MINUTES') }
      steps {
        // Re-checked-out rather than assuming the validate stage's workspace: with per-stage agents
        // the same workspace is not guaranteed. This costs nothing in trust, because
        // deploy-main.ps1 asserts origin, HEAD == origin/main and a clean tree again before it
        // deploys -- the validate stage exists to fail fast, not to be the only check.
        checkout([$class: 'GitSCM',
          branches: [[name: 'refs/heads/main']],
          userRemoteConfigs: [[url: 'https://github.com/Skpandey15/RAMALS.git']]])
        bat label: 'Bootstrap and smoke-test RAMALS', script: '''@echo off
pwsh -NoProfile -NonInteractive -File .\\deploy\\jenkins\\deploy-main.ps1 -ApprovedBy "%RAMALS_APPROVER%"
'''
      }
      post {
        always {
          // Archiving lives on the stage, not the pipeline: with `agent none` a pipeline-level
          // archiveArtifacts has no workspace to read and fails the build for the wrong reason.
          archiveArtifacts artifacts: 'artifacts/jenkins/**', allowEmptyArchive: true,
            fingerprint: true
        }
      }
    }
  }

  post {
    failure {
      // A failed deployment was previously visible only to whoever happened to open Jenkins. This
      // is the hook a notifier belongs on: add mail/Slack here once an endpoint is configured on
      // the controller. Until then it at least makes the failure loud and self-describing in the
      // build log rather than something to be inferred from a red ball.
      echo """
================ RAMALS DEPLOYMENT FAILED ================
Build      : ${env.BUILD_NUMBER}
URL        : ${env.BUILD_URL}
Approved by: ${env.RAMALS_APPROVER ?: '(failed before approval)'}

The cluster may be partially deployed. Check the archived
artifacts/jenkins/summary.json for the failure reason, then
pods.txt and images.txt for what is actually running.
==========================================================
"""
    }
    aborted {
      echo """
=============== RAMALS DEPLOYMENT ABORTED ================
Build : ${env.BUILD_NUMBER}
URL   : ${env.BUILD_URL}

An abort during the deploy stage can leave images pushed and
workloads half-rolled. Verify cluster state before retrying.
==========================================================
"""
    }
  }
}
