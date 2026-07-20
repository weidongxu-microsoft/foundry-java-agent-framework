targetScope = 'subscription'

@description('Azure region used for the subscription deployment record.')
param deploymentLocation string

@description('Resource group to create or reconcile.')
param resourceGroupName string

@description('Azure region for the resource group metadata.')
param resourceGroupLocation string

@description('Tags applied to the resource group.')
param resourceGroupTags object = {}

@description('Azure OpenAI account and deployment configuration.')
param azureOpenAI object

@description('Foundry account, project, and model deployment configuration.')
param foundry object

@description('Azure Container Registry configuration.')
param registry object

@description('Log Analytics, Application Insights, and action-group configuration.')
param monitoring object

@description('Stable role assignments required by the resource topology.')
param rbac object

resource resourceGroup 'Microsoft.Resources/resourceGroups@2024-11-01' = {
  name: resourceGroupName
  location: resourceGroupLocation
  tags: resourceGroupTags
}

module registryModule './modules/registry.bicep' = {
  name: 'openai-registry'
  scope: resourceGroup
  params: {
    registry: registry
  }
}

module monitoringModule './modules/monitoring.bicep' = {
  name: 'openai-monitoring'
  scope: resourceGroup
  params: {
    monitoring: monitoring
  }
}

module aiModule './modules/ai.bicep' = {
  name: 'openai-ai-resources'
  scope: resourceGroup
  params: {
    azureOpenAI: azureOpenAI
    foundry: foundry
  }
}

module rbacModule './modules/rbac.bicep' = {
  name: 'openai-rbac'
  scope: resourceGroup
  dependsOn: [
    registryModule
  ]
  params: {
    azureOpenAIAccountName: azureOpenAI.name
    foundryAccountName: foundry.name
    foundryProjectName: foundry.project.name
    registryName: registry.name
    foundryAccountPrincipalId: aiModule.outputs.foundryAccountPrincipalId
    foundryProjectPrincipalId: aiModule.outputs.foundryProjectPrincipalId
    rbac: rbac
  }
}

output resourceGroupId string = resourceGroup.id
output azureOpenAIAccountId string = aiModule.outputs.azureOpenAIAccountId
output foundryAccountId string = aiModule.outputs.foundryAccountId
output foundryProjectId string = aiModule.outputs.foundryProjectId
output registryLoginServer string = registryModule.outputs.loginServer
output applicationInsightsId string = monitoringModule.outputs.applicationInsightsId
output deploymentRegion string = deploymentLocation
