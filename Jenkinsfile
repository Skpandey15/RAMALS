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

  parameters {
    // A version that failed its health gates and was rolled back is held, and polling will not
    // redeploy it. Overriding is for the case where the environment was the fault rather than the
    // commit -- a flaky cluster, an image registry that was briefly unreachable -- and is
    // deliberately a decision somebody makes rather than a retry that happens by itself.
    booleanParam(name: 'FORCE_HELD_RELEASE', defaultValue: false,
      description: 'Redeploy a commit that was previously rolled back and is currently HELD.')
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
        // Narrowed to main and to no tags. The default refspec fetches every branch and every tag
        // on a workspace that deleteDir() has just emptied, so each build paid for a full clone of
        // refs it never reads. Polling still watches main, because this is still the branch the
        // job is configured against.
        checkout([$class: 'GitSCM',
          branches: [[name: 'refs/heads/main']],
          userRemoteConfigs: [[url: 'https://github.com/Skpandey15/RAMALS.git',
            refspec: '+refs/heads/main:refs/remotes/origin/main']],
          extensions: [[$class: 'CloneOption', noTags: true, honorRefspec: true]]])
        script {
          // The commit this build is about, pinned once and carried to the deploy stage.
          //
          // Without this the deploy stage re-resolves refs/heads/main, which is not necessarily
          // the commit anybody validated or approved: main can advance during the approval window,
          // and deploy-main.ps1's "HEAD must equal origin/main" check is satisfied by the *new*
          // commit just as happily as by the old one. The gate would then have authorised one
          // commit and shipped another -- the single thing a human approval exists to prevent.
          // Parsed defensively rather than trimmed: a bat step's stdout can carry wrapper lines as
          // well as the command's own output, and a mis-parsed value here would be checked out as
          // a ref in the deploy stage. Take the last non-empty line and refuse anything that is
          // not a full SHA, so this fails in the cheap stage instead of at the deployment.
          String revParse = bat(returnStdout: true, label: 'Resolve approved commit',
            script: '@echo off\r\ngit rev-parse HEAD')
          List<String> lines = revParse.readLines().findAll { it.trim() }
          String resolved = lines ? lines.last().trim() : ''
          if (!(resolved ==~ /[0-9a-f]{40}/)) {
            error("Could not resolve the checked-out commit; git rev-parse returned: ${revParse}")
          }
          env.RAMALS_COMMIT = resolved
          echo "Build is pinned to commit ${env.RAMALS_COMMIT}"
        }
      }
    }

    stage('Validate deployment source') {
      agent { label 'windows && ramals-dev' }
      options { timeout(time: 20, unit: 'MINUTES') }
      steps {
        // The held-release check runs here as well as in the deploy stage, so a commit that will
        // be refused is refused before anybody is asked to approve it. Asking a human to authorise
        // a deployment that cannot happen teaches them the prompt does not mean anything.
        script {
          String force = params.FORCE_HELD_RELEASE ? ' -ForceHeldRelease' : ''
          bat label: 'Validate exact main commit', script: """@echo off
pwsh -NoProfile -NonInteractive -File .\\deploy\\jenkins\\deploy-main.ps1 -ValidateOnly${force}
"""
        }
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
          // The commit is named in the prompt because an approver cannot authorise a deployment
          // they were never shown. "Deploy main" is not a decision; "deploy this commit" is.
          env.RAMALS_APPROVER = input(
            message: "Do you want to deploy to the local/dev k3d environment?" +
              "\n\nCommit: ${env.RAMALS_COMMIT}",
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
        //
        // The approved SHA, not refs/heads/main. If main has advanced during the approval window,
        // deploy-main.ps1 now refuses the build because the approved commit is no longer
        // origin/main -- which is the correct direction to fail: re-run and re-approve against the
        // newer commit, rather than silently deploy something nobody looked at.
        checkout([$class: 'GitSCM',
          branches: [[name: env.RAMALS_COMMIT]],
          userRemoteConfigs: [[url: 'https://github.com/Skpandey15/RAMALS.git',
            refspec: '+refs/heads/main:refs/remotes/origin/main']],
          extensions: [[$class: 'CloneOption', noTags: true, honorRefspec: true]]])
        script {
          String force = params.FORCE_HELD_RELEASE ? ' -ForceHeldRelease' : ''
          bat label: 'Bootstrap and smoke-test RAMALS', script: """@echo off
pwsh -NoProfile -NonInteractive -File .\\deploy\\jenkins\\deploy-main.ps1 -ApprovedBy "%RAMALS_APPROVER%"${force}
"""
        }
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
Commit     : ${env.RAMALS_COMMIT ?: '(failed before checkout)'}
Approved by: ${env.RAMALS_APPROVER ?: '(failed before approval)'}

If this failed in deploy-main.ps1 with "HEAD ... is not current
origin/main", main advanced while the build waited for approval.
Nothing was deployed. Re-run and approve the newer commit.

Otherwise the deployment rolled back to the last known-good
version and this commit is now HELD: polling will not redeploy
it. summary.json carries rolledBack and rolledBackTo. If the
rollback reports incomplete, the cluster is mixed and needs a
human before anything else is deployed.

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
Commit: ${env.RAMALS_COMMIT ?: '(aborted before checkout)'}

An abort during the deploy stage can leave images pushed and
workloads half-rolled. Verify cluster state before retrying.
==========================================================
"""
    }
  }
}
