targetScope = 'resourceGroup'

@description('Foundry account name.')
param foundryAccountName string

@description('Foundry project name.')
param foundryProjectName string

@description('Azure Container Registry name.')
param registryName string

@description('System-assigned principal ID of the Foundry account.')
param foundryAccountPrincipalId string

@description('System-assigned principal ID of the Foundry project.')
param foundryProjectPrincipalId string

@description('Stable role assignments required by the resource topology.')
param rbac object

resource foundryAccount 'Microsoft.CognitiveServices/accounts@2026-05-01' existing = {
  name: foundryAccountName
}

resource foundryProject 'Microsoft.CognitiveServices/accounts/projects@2026-05-01' existing = {
  parent: foundryAccount
  name: foundryProjectName
}

resource containerRegistry 'Microsoft.ContainerRegistry/registries@2025-11-01' existing = {
  name: registryName
}

var foundryUserRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  rbac.roleDefinitionIds.foundryUser)
var acrPullRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  rbac.roleDefinitionIds.acrPull)

resource userOnFoundry 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(rbac.userPrincipalId)) {
  name: rbac.assignmentNames.userOnFoundry
  scope: foundryAccount
  properties: {
    principalId: rbac.userPrincipalId
    principalType: 'User'
    roleDefinitionId: foundryUserRoleDefinitionId
  }
}

resource foundryAccountAcrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: rbac.assignmentNames.foundryAccountAcrPull
  scope: containerRegistry
  properties: {
    principalId: foundryAccountPrincipalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: acrPullRoleDefinitionId
  }
}

resource foundryProjectAcrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: rbac.assignmentNames.foundryProjectAcrPull
  scope: containerRegistry
  properties: {
    principalId: foundryProjectPrincipalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: acrPullRoleDefinitionId
  }
}

resource foundryProjectOnFoundry 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: rbac.assignmentNames.foundryProjectOnFoundry
  scope: foundryAccount
  properties: {
    principalId: foundryProjectPrincipalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: foundryUserRoleDefinitionId
  }
}

resource additionalFoundryAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for assignment in rbac.additionalFoundryAccountAssignments: {
    name: assignment.name
    scope: foundryAccount
    properties: {
      principalId: assignment.principalId
      principalType: assignment.principalType
      roleDefinitionId: subscriptionResourceId(
        'Microsoft.Authorization/roleDefinitions',
        assignment.roleDefinitionId)
    }
  }
]

output foundryProjectResourceId string = foundryProject.id
