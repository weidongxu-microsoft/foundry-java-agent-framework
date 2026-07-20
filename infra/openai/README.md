# Azure resource recovery (`openai` resource group)

Recreates/reconciles the Azure resources used by this repo. The Bicep is committed; actual names,
resource IDs, principal IDs, regions, model versions, and capacities live in the gitignored
`.private/openai/main.bicepparam`.

## Coverage

- Resource group (subscription-scope deployment)
- Azure OpenAI account + model deployments
- Foundry account + project + model deployments
- Azure Container Registry
- Log Analytics, Application Insights, smart-detection action group
- Stable RBAC: user access, Foundry account/project ACR pull, project access to Foundry account

Not ARM-managed: ACR image content, Foundry hosted-agent versions/state, memory stores, and runtime
sessions. Restore those after Bicep; see `plan/15-agent-deploy-rest-api.md` and `admin/`.

## Validate

```powershell
az bicep build --file infra\openai\main.bicep

az deployment sub what-if `
  --name openai-recovery-whatif `
  --location eastus `
  --template-file infra\openai\main.bicep `
  --parameters .private\openai\main.bicepparam
```

Or run the checked wrapper:

```powershell
.\infra\openai\what-if.ps1
```

The wrapper fails on unexpected drift. The unchanged baseline currently reports 14 `NoChange`
resources and five provider artifacts only: Foundry-computed A365/project properties and role
assignments whose system-assigned principal IDs remain symbolic during `what-if`. Investigate any
resource replacement/deletion or SKU, model, capacity, identity, network, or other RBAC change.

## Restore

```powershell
az deployment sub create `
  --name openai-recovery `
  --location eastus `
  --template-file infra\openai\main.bicep `
  --parameters .private\openai\main.bicepparam
```

Then rebuild/push the image, recreate/update the hosted agent, reapply its generated-identity role
assignments, and run the `client` smoke scenarios. Never put secret values in Bicep or the private
inventory; provide them from the environment or Key Vault during post-deploy steps.
