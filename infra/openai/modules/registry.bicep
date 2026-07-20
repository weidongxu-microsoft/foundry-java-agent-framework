targetScope = 'resourceGroup'

@description('Azure Container Registry configuration.')
param registry object

resource containerRegistry 'Microsoft.ContainerRegistry/registries@2025-11-01' = {
  name: registry.name
  location: registry.location
  sku: {
    name: registry.skuName
  }
  properties: {
    adminUserEnabled: registry.adminUserEnabled
    anonymousPullEnabled: registry.anonymousPullEnabled
    dataEndpointEnabled: registry.dataEndpointEnabled
    encryption: registry.encryption
    networkRuleBypassAllowedForTasks: registry.networkRuleBypassAllowedForTasks
    networkRuleBypassOptions: registry.networkRuleBypassOptions
    policies: registry.policies
    publicNetworkAccess: registry.publicNetworkAccess
    roleAssignmentMode: registry.roleAssignmentMode
    zoneRedundancy: registry.zoneRedundancy
  }
  tags: registry.tags
}

output registryId string = containerRegistry.id
output loginServer string = containerRegistry.properties.loginServer
