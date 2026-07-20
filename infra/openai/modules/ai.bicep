targetScope = 'resourceGroup'

@description('Azure OpenAI account and deployment configuration.')
param azureOpenAI object

@description('Foundry account, project, and model deployment configuration.')
param foundry object

resource azureOpenAIAccount 'Microsoft.CognitiveServices/accounts@2026-05-01' = {
  name: azureOpenAI.name
  location: azureOpenAI.location
  kind: 'OpenAI'
  sku: {
    name: azureOpenAI.skuName
  }
  properties: {
    customSubDomainName: azureOpenAI.customSubDomainName
    networkAcls: azureOpenAI.networkAcls
    publicNetworkAccess: azureOpenAI.publicNetworkAccess
  }
  tags: azureOpenAI.tags
}

@batchSize(1)
resource azureOpenAIDeployments 'Microsoft.CognitiveServices/accounts/deployments@2026-05-01' = [
  for deployment in azureOpenAI.deployments: {
    parent: azureOpenAIAccount
    name: deployment.name
    sku: {
      name: deployment.sku.name
      capacity: deployment.sku.capacity
    }
    properties: union({
      currentCapacity: deployment.sku.capacity
      deploymentState: deployment.deploymentState
      model: deployment.model
      raiPolicyName: deployment.raiPolicyName
      versionUpgradeOption: deployment.versionUpgradeOption
    }, contains(deployment, 'serviceTier') ? {
      serviceTier: deployment.serviceTier
    } : {})
  }
]

resource foundryAccount 'Microsoft.CognitiveServices/accounts@2026-05-01' = {
  name: foundry.name
  location: foundry.location
  kind: 'AIServices'
  sku: {
    name: foundry.skuName
  }
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    allowProjectManagement: true
    apiProperties: {}
    associatedProjects: [
      foundry.project.name
    ]
    customSubDomainName: foundry.customSubDomainName
    defaultProject: foundry.project.name
    publicNetworkAccess: foundry.publicNetworkAccess
  }
  tags: foundry.tags
}

resource foundryProject 'Microsoft.CognitiveServices/accounts/projects@2026-05-01' = {
  parent: foundryAccount
  name: foundry.project.name
  location: foundry.project.location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {}
  tags: foundry.project.tags
}

@batchSize(1)
resource foundryDeployments 'Microsoft.CognitiveServices/accounts/deployments@2026-05-01' = [
  for deployment in foundry.deployments: {
    parent: foundryAccount
    name: deployment.name
    sku: {
      name: deployment.sku.name
      capacity: deployment.sku.capacity
    }
    properties: union({
      currentCapacity: deployment.sku.capacity
      deploymentState: deployment.deploymentState
      model: deployment.model
      raiPolicyName: deployment.raiPolicyName
      versionUpgradeOption: deployment.versionUpgradeOption
    }, contains(deployment, 'serviceTier') ? {
      serviceTier: deployment.serviceTier
    } : {})
  }
]

output azureOpenAIAccountId string = azureOpenAIAccount.id
output foundryAccountId string = foundryAccount.id
output foundryAccountPrincipalId string = foundryAccount.identity.principalId
output foundryProjectId string = foundryProject.id
output foundryProjectPrincipalId string = foundryProject.identity.principalId
