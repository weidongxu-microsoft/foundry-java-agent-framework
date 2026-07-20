param(
    [string]$Location = "eastus",
    [string]$ParametersFile = ".private\openai\main.bicepparam"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$templateFile = Join-Path $PSScriptRoot "main.bicep"
$resolvedParameters = Resolve-Path (Join-Path $repoRoot $ParametersFile)

$raw = & az deployment sub what-if `
    --name openai-recovery-whatif `
    --location $Location `
    --template-file $templateFile `
    --parameters $resolvedParameters `
    --no-pretty-print
if ($LASTEXITCODE -ne 0) {
    throw "Azure what-if failed with exit code $LASTEXITCODE."
}

$result = ($raw -join [Environment]::NewLine) | ConvertFrom-Json -Depth 100
$unexpected = [System.Collections.Generic.List[object]]::new()

foreach ($change in $result.changes) {
    if ($change.changeType -eq "NoChange") {
        continue
    }

    $hasDeferredPrincipalId = @($change.delta | Where-Object {
        $_.path -eq "properties.principalId" -and
        [string]$_.after -match "^\[reference\("
    }).Count -gt 0

    foreach ($delta in $change.delta) {
        $isFoundryComputedAccountProperty =
            $change.resourceId -match "/Microsoft\.CognitiveServices/accounts/[^/]+$" -and
            $delta.path -in @("properties.a365LoggingEnabled", "properties.a365Status")

        $isFoundryComputedProjectProperty =
            $change.resourceId -match "/Microsoft\.CognitiveServices/accounts/[^/]+/projects/[^/]+$" -and
            $delta.path -in @("kind", "properties")

        $isDeferredPrincipalReference =
            $change.resourceId -match "/Microsoft\.Authorization/roleAssignments/" -and (
                ($delta.path -eq "properties.principalId" -and
                    [string]$delta.after -match "^\[reference\(") -or
                ($delta.path -eq "properties.principalType" -and
                    $delta.propertyChangeType -eq "NoEffect" -and
                    $hasDeferredPrincipalId)
            )

        if (-not ($isFoundryComputedAccountProperty -or
                $isFoundryComputedProjectProperty -or
                $isDeferredPrincipalReference)) {
            $unexpected.Add([pscustomobject]@{
                ChangeType = $change.changeType
                ResourceId = $change.resourceId
                Path = $delta.path
                PropertyChangeType = $delta.propertyChangeType
                Before = $delta.before
                After = $delta.after
            })
        }
    }
}

$summary = $result.changes |
    Group-Object changeType |
    Sort-Object Name |
    ForEach-Object { "$($_.Name)=$($_.Count)" }
Write-Host "What-if summary: $($summary -join ', ')"

if ($unexpected.Count -gt 0) {
    $unexpected | Format-Table -AutoSize -Wrap
    throw "What-if contains $($unexpected.Count) unexpected delta(s)."
}

Write-Host "PASS: no unintended infrastructure changes."
Write-Host "Known provider noise is limited to Foundry computed properties and deferred identity references."
