targetScope = 'resourceGroup'

@description('Foundry account, project, and model deployment configuration.')
param foundry object

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

output foundryAccountId string = foundryAccount.id
output foundryAccountPrincipalId string = foundryAccount.identity.principalId
output foundryProjectId string = foundryProject.id
output foundryProjectPrincipalId string = foundryProject.identity.principalId
