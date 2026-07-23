targetScope = 'resourceGroup'

@description('Storage account and blob container configuration for app data (e.g. RAW uploads).')
param storage object

resource storageAccount 'Microsoft.Storage/storageAccounts@2024-01-01' = {
  name: storage.name
  location: storage.location
  sku: {
    name: storage.skuName
  }
  kind: storage.kind
  properties: {
    accessTier: storage.accessTier
    minimumTlsVersion: storage.minimumTlsVersion
    allowBlobPublicAccess: storage.allowBlobPublicAccess
    publicNetworkAccess: storage.publicNetworkAccess
  }
  tags: storage.tags
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2024-01-01' = {
  parent: storageAccount
  name: 'default'
}

resource containers 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = [
  for name in storage.containers: {
    parent: blobService
    name: name
    properties: {
      publicAccess: 'None'
    }
  }
]

output storageAccountId string = storageAccount.id
output storageAccountName string = storageAccount.name
