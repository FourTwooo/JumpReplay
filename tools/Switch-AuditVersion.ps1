param([ValidateSet('Baseline', 'Fixed')][string]$Version = 'Baseline')
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$branches = @{
    Baseline = 'codex/rollback-before-audit-20261110-170843'
    Fixed = 'codex/audit-fixes-20261110-170843'
}
Push-Location -LiteralPath $repository
try {
    $changes = @(git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect repository status.' }
    if ($changes.Count -gt 0) {
        throw 'Working tree has changes. Save or commit them before switching; this script never overwrites your work.'
    }
    git switch $branches[$Version]
    if ($LASTEXITCODE -ne 0) { throw 'Version switch failed.' }
    git log -1 --oneline
} finally { Pop-Location }
