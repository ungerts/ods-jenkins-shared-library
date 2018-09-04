package org.ods

class OdsContext implements Context {

  def script
  Logger logger
  Map config

  boolean environmentCreated
  boolean branchUpdated

  OdsContext(script, config, logger) {
    this.script = script
    this.config = config
    this.logger = logger
  }

  def assemble() {
    logger.verbose "Validating input ..."
    if (!config.projectId) {
      logger.error "Param 'projectId' is required"
    }
    if (!config.componentId) {
      logger.error "Param 'componentId' is required"
    }
    if (!config.image) {
      logger.error "Param 'image' is required"
    }

    logger.verbose "Collecting environment variables ..."
    config.jobName = script.env.JOB_NAME
    config.buildNumber = script.env.BUILD_NUMBER
    config.buildUrl = script.env.BUILD_URL
    config.nexusHost = script.env.NEXUS_HOST
    config.nexusUsername = script.env.NEXUS_USERNAME
    config.nexusPassword = script.env.NEXUS_PASSWORD
    config.branchName = script.env.BRANCH_NAME // may be empty
    config.openshiftHost = script.env.OPENSHIFT_API_URL
    config.bitbucketHost = script.env.BITBUCKET_HOST

    logger.verbose "Validating environment variables ..."
    if (!config.jobName) {
      logger.error 'JOB_NAME is required, but not set (usually provided by Jenkins)'
    }
    if (!config.buildNumber) {
      logger.error 'BUILD_NUMBER is required, but not set (usually provided by Jenkins)'
    }
    if (!config.buildUrl) {
      logger.error 'BUILD_URL is required, but not set (usually provided by Jenkins)'
    }
    if (!config.nexusHost) {
      logger.error 'NEXUS_HOST is required, but not set'
    }
    if (!config.nexusUsername) {
      logger.error 'NEXUS_USERNAME is required, but not set'
    }
    if (!config.nexusPassword) {
      logger.error 'NEXUS_PASSWORD is required, but not set'
    }
    if (!config.openshiftHost) {
      logger.error 'OPENSHIFT_API_URL is required, but not set'
    }
    if (!config.bitbucketHost) {
      logger.error 'BITBUCKET_HOST is required, but not set'
    }

    logger.verbose "Deriving configuration from input ..."
    config.openshiftProjectId = "${config.projectId}-cd"
    config.credentialsId = config.openshiftProjectId + '-cd-user-with-password'

    logger.verbose "Setting defaults ..."
    if (!config.containsKey('workflow')) {
      config.workflow = "GitHub Flow"
    }
    if (config.workflow != "git-flow" && config.workflow != "GitHub Flow") {
      logger.error 'Aborting! workflow must be either "git-flow" or ' +
                   '"GitHub Flow", but was "' + config.workflow + '".'
    }
    config.autoCreateReviewEnvironment = config.autoCreateReviewEnvironment ?: false
    config.autoCreateReleaseEnvironment = config.autoCreateReleaseEnvironment ?: false
    config.autoCreateHotfixEnvironment = config.autoCreateHotfixEnvironment ?: false
    if (config.autoCreateEnvironment) {
      config.autoCreateReviewEnvironment = true
      config.autoCreateReleaseEnvironment = true
      config.autoCreateHotfixEnvironment = true
    }
    config.updateBranch = config.updateBranch ?: false
    if (!config.containsKey('notifyNotGreen')) {
      config.notifyNotGreen = true
    }
    if (!config.containsKey('environmentLimit')) {
      config.environmentLimit = 5
    }
    if (!config.containsKey('openshiftBuildTimeout')) {
      config.openshiftBuildTimeout = 15
    }
    if (!config.groupId) {
      config.groupId = "org.opendevstack.${config.projectId}"
    }
    if (config.testProjectBranch) {
      logger.echo 'Caution! testProjectBranch is deprecated. Set ' +
                  'productionBranch and/or productionEnvironment.'
      config.productionBranch = config.testProjectBranch
      config.productionEnvironment = "test"
    }
    if (!config.productionBranch) {
      config.productionBranch = "master"
    }
    if (!config.productionEnvironment) {
      config.productionEnvironment = "prod"
    }
    if (!config.developmentBranch) {
      config.developmentBranch = "develop"
    }
    if (!config.developmentEnvironment) {
      config.developmentEnvironment = "dev"
    }
    if (!config.defaultReviewEnvironment) {
      config.defaultReviewEnvironment = "review"
    }
    if (!config.defaultHotfixEnvironment) {
      config.defaultHotfixEnvironment = "hotfix"
    }
    if (!config.defaultReleaseEnvironment) {
      config.defaultReleaseEnvironment = "release"
    }
    if (!config.podVolumes) {
      config.podVolumes = []
    }
    if (!config.containsKey('podAlwaysPullImage')) {
      config.podAlwaysPullImage = true
    }

    config.responsible = true

    logger.verbose "Retrieving Git information ..."
    config.gitUrl = retrieveGitUrl()

    // BRANCH_NAME is only given for "Bitbucket Team/Project" items. For those,
    // we need to do a little bit of magic to get the right Git branch.
    // For other pipelines, we need to check responsibility.
    if (config.branchName) {
      if (config.branchName.startsWith("PR-")){
        config.gitBranch = retrieveBranchOfPullRequest(config.credentialsId, config.gitUrl, config.branchName)
        config.jobName = config.branchName
      } else {
        config.gitBranch = config.branchName
        config.jobName = extractBranchCode(config.branchName)
      }
    } else {
      config.gitBranch = determineBranchToBuild(config.credentialsId, config.gitUrl)
      checkoutBranch(config.gitBranch)
      config.gitCommit = retrieveGitCommit()
      if (!isResponsible()) {
        script.currentBuild.displayName = "${config.buildNumber}/skipping-not-responsible"
        logger.verbose "This job: ${config.jobName} is not responsible for building: ${config.gitBranch}"
        config.responsible = false
      }
    }

    config.shortBranchName = extractShortBranchName(config.gitBranch)
    config.tagversion = "${config.buildNumber}-${config.gitCommit.take(8)}"

    logger.verbose "Setting environment ..."
    config.environment = determineEnvironment()
    if (config.environment) {
      config.targetProject = "${config.projectId}-${config.environment}"
      if (assumedEnvironments.contains(config.environment)) {
        config.cloneSourceEnv = ""
      } else {
        config.cloneSourceEnv = config.productionEnvironment
      }
      if (config.workflow == "git-flow") {
        if (config.environment.startsWith("release-") || config.environment.startsWith("review-") || [config.defaultReleaseEnvironment, config.defaultReviewEnvironment].contains(config.environment)) {
          config.cloneSourceEnv = config.developmentEnvironment
        }
      }
    }

    logger.verbose "Assembled configuration: ${config}"
  }

  boolean getVerbose() {
      config.verbose
  }

  String getJobName() {
    config.jobName
  }

  String getBuildNumber() {
    config.buildNumber
  }

  String getBuildUrl() {
    config.buildUrl
  }

  boolean getResponsible() {
    config.responsible
  }

  boolean getUpdateBranch() {
      config.updateBranch
  }

  String getGitBranch() {
      config.gitBranch
  }

  String getCredentialsId() {
      config.credentialsId
  }

  String getImage() {
    config.image
  }

  String getPodLabel() {
    "pod-${simpleHash(config.image)}"
  }

  Object getPodVolumes() {
    config.podVolumes
  }

  boolean getPodAlwaysPullImage() {
    config.podAlwaysPullImage
  }

  boolean getBranchUpdated() {
      branchUpdated
  }

  String getGitUrl() {
      config.gitUrl
  }

  String getShortBranchName() {
      config.shortBranchName
  }

  String getTagversion() {
      config.tagversion
  }

  boolean getNotifyNotGreen() {
      config.notifyNotGreen
  }

  String getNexusHost() {
      config.nexusHost
  }

  String getNexusUsername() {
      config.nexusUsername
  }

  String getNexusPassword() {
      config.nexusPassword
  }

  String getProductionBranch() {
      config.productionBranch
  }

  String getProductionEnvironment() {
      config.productionEnvironment
  }

  String getCloneSourceEnv() {
      config.cloneSourceEnv
  }

  String getEnvironment() {
      config.environment
  }

  String getGroupId() {
      config.groupId
  }

  String getProjectId() {
      config.projectId
  }

  String getComponentId() {
      config.componentId
  }

  String getGitCommit() {
      config.gitCommit
  }

  String getTargetProject() {
      config.targetProject
  }

  int getEnvironmentLimit() {
      config.environmentLimit
  }

  boolean getAdmins() {
      config.admins
  }

  String getOpenshiftHost() {
      config.openshiftHost
  }

  String getBitbucketHost() {
      config.bitbucketHost
  }

  boolean getEnvironmentCreated() {
      this.environmentCreated
  }

  int getOpenshiftBuildTimeout() {
      config.openshiftBuildTimeout
  }

  def setEnvironmentCreated(boolean created) {
      this.environmentCreated = created
  }

  boolean shouldUpdateBranch() {
    config.responsible && config.updateBranch && config.productionBranch != config.gitBranch
  }

  def setBranchUpdated(boolean branchUpdated) {
      this.branchUpdated = branchUpdated
  }

  String[] getAssumedEnvironments() {
    return [
      config.productionEnvironment,
      config.developmentEnvironment,
      config.defaultReviewEnvironment,
      config.defaultHotfixEnvironment,
      config.defaultReleaseEnvironment
    ]
  }

  // We cannot use java.security.MessageDigest.getInstance("SHA-256")
  // nor hashCode() due to sandbox restrictions ...
  private int simpleHash(String str) {
    int hash = 7;
    for (int i = 0; i < str.length(); i++) {
      hash = hash*31 + str.charAt(i);
    }
    return hash
  }

  private String retrieveGitUrl() {
    script.sh(
      returnStdout: true, script: 'git config --get remote.origin.url'
    ).trim().replace('https://bitbucket', 'https://cd_user@bitbucket')
  }

  private String retrieveGitCommit() {
      script.sh(
        returnStdout: true, script: 'git rev-parse HEAD'
      ).trim()
  }

  // Pipelines ending in either "-test" or "-prod" are only responsible for the
  // production branch (typically master). "-master" pipelines are responsible
  // for master branch. Other pipelines are responsible for all other branches.
  // This works because BitBucket items only trigger the correct pipeline, and
  // in the other case the "-dev" pipeline should build all branches. 
  private boolean isResponsible() {
    if (config.jobName.endsWith("-test") || config.jobName.endsWith("-prod")) {
      config.productionBranch.equals(config.gitBranch)
    } else if (config.jobName.endsWith("-master")) {
       config.gitBranch == "master"
    } else {
      !config.productionBranch.equals(config.gitBranch)
    }
  }

  // Given a branch like "feature/HUGO-4-brown-bag-lunch", it extracts
  // "HUGO-4-brown-bag-lunch" from it.
  private String extractShortBranchName(String branch) {
    if (config.productionBranch.equals(branch)) {
      branch
    } else if (branch.startsWith("feature/")) {
      branch.drop("feature/".length())
    } else if (branch.startsWith("bugfix/")) {
      branch.drop("bugfix/".length())
    } else if (branch.startsWith("hotfix/")) {
      branch.drop("hotfix/".length())
    } else if (branch.startsWith("release/")) {
      branch.drop("release/".length())
    } else {
      branch
    }
  }

  // Given a branch like "feature/HUGO-4-brown-bag-lunch", it extracts
  // "HUGO-4" from it.
  private String extractBranchCode(String branch) {
      if (config.productionBranch.equals(branch)) {
          branch
      } else if (branch.startsWith("feature/")) {
          def list = branch.drop("feature/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else if (branch.startsWith("bugfix/")) {
          def list = branch.drop("bugfix/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else if (branch.startsWith("hotfix/")) {
          def list = branch.drop("hotfix/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else if (branch.startsWith("release/")) {
          def list = branch.drop("release/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else {
          branch
      }
  }

  // For pull requests, the branch name environment variable is not the actual
  // git branch, which we need.
  private String retrieveBranchOfPullRequest(String credId, String gitUrl, String pullRequest){
    script.withCredentials([script.usernameColonPassword(credentialsId: credId, variable: 'USERPASS')]) {
      def url = constructCredentialBitbucketURL(gitUrl, script.USERPASS)
      def pullRequestNumber = pullRequest.drop("PR-".length())
      def commitNumber = script.withEnv(["BITBUCKET_URL=${url}", "PULL_REQUEST_NUMBER=${pullRequestNumber}"]) {
        return script.sh(returnStdout: true, script: '''
          git config user.name "Jenkins CD User"
          git config user.email "cd_user@opendevstack.org"
          git config credential.helper store
          echo ${BITBUCKET_URL} > ~/.git-credentials
          git fetch
          git ls-remote | grep \"refs/pull-requests/${PULL_REQUEST_NUMBER}/from\" | awk \'{print \$1}\'
        ''').trim()
      }
      def branch = script.withEnv(["BITBUCKET_URL=${url}", "COMMIT_NUMBER=${commitNumber}"]) {
        return script.sh(returnStdout: true, script: '''
          git config user.name "Jenkins CD User"
          git config user.email "cd_user@opendevstack.org"
          git config credential.helper store
          echo ${BITBUCKET_URL} > ~/.git-credentials
          git fetch
          git ls-remote | grep ${COMMIT_NUMBER} | grep \'refs/heads\' | awk \'{print \$2}\'
        ''').trim().drop("refs/heads/".length())
      }
      return branch
    }
  }

  // If BRANCH_NAME is not given, we need to figure out the branch from the last
  // commit to the repository.
  private String determineBranchToBuild(credentialsId, gitUrl) {
    script.withCredentials([script.usernameColonPassword(credentialsId: credentialsId, variable: 'USERPASS')]) {
      def url = constructCredentialBitbucketURL(gitUrl, script.USERPASS)
      script.withEnv(["BITBUCKET_URL=${url}"]) {
        return script.sh(returnStdout: true, script: '''
          git config user.name "Jenkins CD User"
          git config user.email "cd_user@opendevstack.org"
          git config credential.helper store
          echo ${BITBUCKET_URL} > ~/.git-credentials
          git fetch
          git for-each-ref --sort=-committerdate refs/remotes/origin | cut -c69- | head -1
        ''').trim()
      }
    }
  }

  private String constructCredentialBitbucketURL(String url, String userPass) {
      return url.replace("cd_user", userPass)
  }

  private String buildGitUrl(credentialsId) {
    def token
    script.withCredentials([script.usernameColonPassword(credentialsId: credentialsId, variable: 'USERPASS')]) {
      token = 'https://' + script.USERPASS + '@bitbucket'
    }
    return script.sh(
      returnStdout: true, script: 'git config --get remote.origin.url'
    ).trim().replace('https://bitbucket', token)
  }

  // This logic must be consistent with what is described in README.md.
  // To make it easier to follow the logic, it is broken down by workflow (at
  // the cost of having some duplication).
  String determineEnvironment() {
    String noDeploymentMsg = "No environment to deploy to was determined " +
      "[gitBranch=${config.gitBranch}, projectId=${config.projectId}]"
    String env = ""

    if (config.workflow == "git-flow") {
      // production
      if (config.gitBranch == config.productionBranch) {
        return config.productionEnvironment
      }

      // development
      if (config.gitBranch == config.developmentBranch) {
        return config.developmentEnvironment
      }

      // hotfix
      if (config.gitBranch.startsWith("hotfix/")) {
        def ticketId = getTicketIdFromBranch(config.gitBranch, config.projectId)
        if (ticketId) {
          env = "hotfix-${ticketId}"
          if (config.autoCreateHotfixEnvironment || environmentExists(env)) {
            return env
          }
        }
        env = config.defaultHotfixEnvironment
        if (environmentExists(env)) {
          return env
        }
        logger.echo "Default hotfix environment (${env}) does not exist. " +
                    "Create it manually via the cloning script to deploy to it."
        logger.echo noDeploymentMsg
        return ""
      }

      // release
      if (config.gitBranch.startsWith("release/")) {
        def version = config.gitBranch.split("/")[1]
        if (version) {
          env = "release-${version}"
          if (config.autoCreateReleaseEnvironment || environmentExists(env)) {
            return env
          }
        }
        env = config.defaultReleaseEnvironment
        if (environmentExists(env)) {
          return env
        }
        logger.echo "Default release environment (${env}) does not exist. " +
                    "Create it manually via the cloning script to deploy to it."
        logger.echo noDeploymentMsg
        return ""
      }

      // review
      def ticketId = getTicketIdFromBranch(config.gitBranch, config.projectId)
      if (ticketId) {
        env = "review-${ticketId}"
        if (config.autoCreateReviewEnvironment || environmentExists(env)) {
          return env
        }
      }
      env = config.defaultReviewEnvironment
      if (environmentExists(env)) {
        return env
      }
      logger.echo "Default review environment (${env}) does not exist. " +
                  "Create it manually via the cloning script to deploy to it."
      logger.echo noDeploymentMsg
      return ""
    }

    if (config.workflow == "GitHub Flow") {
      // production
      if (config.gitBranch == config.productionBranch) {
        return config.productionEnvironment
      }

      // review
      def ticketId = getTicketIdFromBranch(config.gitBranch, config.projectId)
      if (ticketId) {
        env = "review-${ticketId}"
        if (config.autoCreateReviewEnvironment || environmentExists(env)) {
          return env
        }
      }
      env = config.defaultReviewEnvironment
      if (environmentExists(env)) {
        return env
      }
      logger.echo "Default review environment (${env}) does not exist. " +
                  "Create it manually via the cloning script to deploy to it."
      logger.echo noDeploymentMsg
      return ""
    }
  }

  protected String getTicketIdFromBranch(String branchName, String projectId) {
    def tokens = extractBranchCode(branchName).split("-")
    def pId = tokens[0]
    if (!pId || !pId.equalsIgnoreCase(projectId)) {
      return ""
    }
    return tokens[1]
  }

  protected String checkoutBranch(String branchName) {
    script.git url: config.gitUrl, branch: branchName, credentialsId: config.credentialsId
  }

  protected boolean environmentExists(String name) {
    def statusCode = script.sh(
      script:"oc project ${name} &> /dev/null",
      returnStatus: true
    )
    return statusCode == 0
  }

}
