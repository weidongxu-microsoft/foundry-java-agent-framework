targetScope = 'resourceGroup'

@description('Log Analytics, Application Insights, and action-group configuration.')
param monitoring object

resource logAnalyticsWorkspace 'Microsoft.OperationalInsights/workspaces@2025-07-01' = {
  name: monitoring.logAnalytics.name
  location: monitoring.logAnalytics.location
  properties: {
    features: monitoring.logAnalytics.features
    publicNetworkAccessForIngestion: monitoring.logAnalytics.publicNetworkAccessForIngestion
    publicNetworkAccessForQuery: monitoring.logAnalytics.publicNetworkAccessForQuery
    retentionInDays: monitoring.logAnalytics.retentionInDays
    sku: {
      name: monitoring.logAnalytics.skuName
    }
    workspaceCapping: {
      dailyQuotaGb: monitoring.logAnalytics.dailyQuotaGb
    }
  }
  tags: monitoring.logAnalytics.tags
}

resource applicationInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: monitoring.applicationInsights.name
  location: monitoring.applicationInsights.location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    Flow_Type: 'Bluefield'
    IngestionMode: 'LogAnalytics'
    Request_Source: 'rest'
    RetentionInDays: monitoring.applicationInsights.retentionInDays
    WorkspaceResourceId: logAnalyticsWorkspace.id
    publicNetworkAccessForIngestion: monitoring.applicationInsights.publicNetworkAccessForIngestion
    publicNetworkAccessForQuery: monitoring.applicationInsights.publicNetworkAccessForQuery
  }
  tags: monitoring.applicationInsights.tags
}

resource smartDetectionActionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: monitoring.smartDetectionActionGroup.name
  location: 'global'
  properties: monitoring.smartDetectionActionGroup.properties
  tags: monitoring.smartDetectionActionGroup.tags
}

output logAnalyticsWorkspaceId string = logAnalyticsWorkspace.id
output applicationInsightsId string = applicationInsights.id
output smartDetectionActionGroupId string = smartDetectionActionGroup.id
