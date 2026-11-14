#Requires -Version 5.1
<#
.SYNOPSIS
    Phase-completion gate for the loqui repo: build, test, commit, push.

.DESCRIPTION
    Enforces the phase-completion rule in 06-agent-workflow.md. Runs in order:

      1. Preflight    git present, repo root, branch is main, origin configured.
      2. Build+tests  .\gradlew.bat assembleDebug testDebugUnitTest must pass.
      3. Commit       if the tree is dirty, commit as "phase-N: <description>".
      4. Tip check    HEAD subject must start with "phase-N:".
      5. Fast-forward local main must contain origin/main; force is never needed.
      6. Push         git push origin main (never --force), then verify SHAs.

    Any failed guard stops the run with exit code 1 and pushes nothing.

.PARAMETER Phase
    Phase number, e.g. 1 for Phase 1. Used as the "phase-N:" commit prefix.

.PARAMETER Message
    Short description used as "phase-N: <description>" when committing a dirty
    tree. Required whenever the working tree is dirty.

.PARAMETER DryRun
    Run the read-only checks and print the plan. No build, no commit, no push.

.EXAMPLE
    .\scripts\phase-done.ps1 -Phase 1 -Message "skeleton"

.EXAMPLE
    .\scripts\phase-done.ps1 -Phase 1 -DryRun

.NOTES
    This script has no force-push path. "git push --force" and friends are
    rejected by Invoke-Git before a git process is ever started.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^\d+$')]
    [string] $Phase,

    [Parameter(Position = 1)]
    [AllowEmptyString()]
    [string] $Message = '',

    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$CommitPrefix = "phase-${Phase}:"
$TargetBranch = 'main'
$TargetRemote = 'origin'

# Never allowed, in any position, for any git invocation in this script.
$ForbiddenGitArgs = @('--force', '-f', '--force-with-lease', '--force-if-includes')

function Write-Step {
    param([Parameter(Mandatory = $true)][string] $Text)
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Stop-Gate {
    param(
        [Parameter(Mandatory = $true)][string] $Reason,
        [string] $Hint = ''
    )
    Write-Host ''
    Write-Host 'PHASE NOT DONE - nothing was pushed.' -ForegroundColor Red
    Write-Host "  reason: $Reason" -ForegroundColor Red
    if ($Hint) { Write-Host "  next:   $Hint" -ForegroundColor Yellow }
    exit 1
}

function Invoke-Git {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string[]] $Arguments)

    foreach ($argument in $Arguments) {
        if ($ForbiddenGitArgs -contains $argument) {
            throw "Refusing 'git $($Arguments -join ' ')': this script never force-pushes."
        }
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = & git @Arguments 2>&1
        $code = $LASTEXITCODE
        $text = ($lines | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    }
    finally {
        $ErrorActionPreference = $previous
    }

    [pscustomobject]@{ ExitCode = $code; Output = $text.Trim() }
}

# ---------------------------------------------------------------------------
# 1. Preflight
# ---------------------------------------------------------------------------
Write-Step "preflight (phase $Phase)"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Stop-Gate 'git is not on PATH.' 'Install Git for Windows and reopen the shell.'
}

$probe = Invoke-Git -Arguments @('rev-parse', '--show-toplevel')
if ($probe.ExitCode -ne 0) {
    Stop-Gate "not inside a git work tree: $PSScriptRoot" 'Run this script from the loqui checkout.'
}

$RepoRoot = [IO.Path]::GetFullPath($probe.Output).TrimEnd('\', '/')
$ScriptRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\', '/')
if ($RepoRoot -ne $ScriptRoot) {
    Stop-Gate "script lives outside the repo it would push: script=$ScriptRoot repo=$RepoRoot" `
        'Keep scripts/phase-done.ps1 inside the loqui checkout.'
}

$branch = Invoke-Git -Arguments @('rev-parse', '--abbrev-ref', 'HEAD')
if ($branch.ExitCode -ne 0) {
    Stop-Gate 'could not determine the current branch.' 'git status, then re-run.'
}
if ($branch.Output -ne $TargetBranch) {
    Stop-Gate "current branch is '$($branch.Output)', not '$TargetBranch'." `
        "git switch $TargetBranch, then re-run."
}

$remoteUrl = Invoke-Git -Arguments @('remote', 'get-url', $TargetRemote)
if ($remoteUrl.ExitCode -ne 0) {
    Stop-Gate "no '$TargetRemote' remote configured." "git remote add $TargetRemote <url>"
}

$GradleBat = Join-Path $RepoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $GradleBat -PathType Leaf)) {
    Stop-Gate "gradlew.bat not found in $RepoRoot." 'Restore the Gradle wrapper before declaring a phase done.'
}

Write-Host "    repo:   $RepoRoot"
Write-Host "    branch: $($branch.Output)"
Write-Host "    remote: $($remoteUrl.Output)"

# ---------------------------------------------------------------------------
# Dry run: stop here, having changed nothing.
# ---------------------------------------------------------------------------
if ($DryRun) {
    $dryStatus = Invoke-Git -Arguments @('status', '--porcelain')
    $dryHead = Invoke-Git -Arguments @('log', '-1', '--pretty=%h %s')

    Write-Host ''
    Write-Host 'DRY RUN - no build, no commit, no push.' -ForegroundColor Yellow
    Write-Host "  HEAD        : $($dryHead.Output)"
    if ([string]::IsNullOrWhiteSpace($dryStatus.Output)) {
        Write-Host '  tree        : clean'
    }
    else {
        Write-Host '  tree        : dirty'
        $dryStatus.Output -split "`r?`n" | ForEach-Object { Write-Host "                $_" }
    }
    Write-Host "  would run   : $GradleBat assembleDebug testDebugUnitTest"
    Write-Host "  would commit: $CommitPrefix <description>"
    Write-Host "  would push  : git push $TargetRemote $TargetBranch"
    Write-Host ''
    Write-Host 'Dry run finished - preflight guards passed.' -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------------------
# 2. Build + unit tests. Either failure means we do not push.
# ---------------------------------------------------------------------------
Write-Step 'build + tests: gradlew.bat assembleDebug testDebugUnitTest'

$buildExit = 1
Push-Location -LiteralPath $RepoRoot
try {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $GradleBat assembleDebug testDebugUnitTest
        $buildExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
}
finally {
    Pop-Location
}

if ($buildExit -ne 0) {
    Stop-Gate "gradlew exited with code $buildExit (assembleDebug / testDebugUnitTest failed)." `
        'Fix the build or the failing test. Never delete a test or silence lint to go green.'
}
Write-Host '    assembleDebug + testDebugUnitTest PASSED' -ForegroundColor Green

# ---------------------------------------------------------------------------
# 3. Commit the phase work if the tree is dirty.
# ---------------------------------------------------------------------------
Write-Step 'commit'

$status = Invoke-Git -Arguments @('status', '--porcelain')
if ($status.ExitCode -ne 0) {
    Stop-Gate "git status failed: $($status.Output)" 'Inspect the repository state manually.'
}

if (-not [string]::IsNullOrWhiteSpace($status.Output)) {
    if ([string]::IsNullOrWhiteSpace($Message)) {
        Stop-Gate 'the working tree is dirty and no -Message was given.' `
            'Re-run with -Message "<short description>" so the commit reads phase-N: <description>.'
    }

    Write-Host '    staging:'
    $status.Output -split "`r?`n" | ForEach-Object { Write-Host "      $_" }

    $add = Invoke-Git -Arguments @('add', '--all')
    if ($add.ExitCode -ne 0) {
        Stop-Gate "git add --all failed: $($add.Output)" 'Resolve the index state, then re-run.'
    }

    $commitMessage = "$CommitPrefix $Message"
    $commit = Invoke-Git -Arguments @('commit', '-m', $commitMessage)
    if ($commit.ExitCode -ne 0) {
        Stop-Gate "git commit failed: $($commit.Output)" 'Resolve the commit failure, then re-run.'
    }
    Write-Host "    committed: $commitMessage" -ForegroundColor Green
}
else {
    Write-Host '    tree already clean - nothing to commit'
}

# ---------------------------------------------------------------------------
# 4. The tip of main must be this phase's commit.
# ---------------------------------------------------------------------------
Write-Step "verify HEAD is a $CommitPrefix commit"

$subject = Invoke-Git -Arguments @('log', '-1', '--pretty=%s')
if ($subject.ExitCode -ne 0) {
    Stop-Gate 'could not read the HEAD commit subject.' 'Inspect git log manually.'
}
if (-not $subject.Output.StartsWith($CommitPrefix, [StringComparison]::Ordinal)) {
    Stop-Gate "HEAD is '$($subject.Output)', which does not start with '$CommitPrefix'." `
        "Commit the phase work as '$CommitPrefix <short description>' before pushing."
}
Write-Host "    HEAD: $($subject.Output)" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 5. Fast-forward check - a push must not need a force.
# ---------------------------------------------------------------------------
Write-Step "fast-forward check against $TargetRemote/$TargetBranch (no force-push)"

$fetch = Invoke-Git -Arguments @('fetch', '--quiet', $TargetRemote, $TargetBranch)
if ($fetch.ExitCode -ne 0) {
    Stop-Gate "git fetch $TargetRemote $TargetBranch failed: $($fetch.Output)" `
        'Check network and credentials. Never work around a rejected push with a force.'
}

$aheadCount = 0
$remoteRef = Invoke-Git -Arguments @('rev-parse', '--verify', '--quiet', "$TargetRemote/$TargetBranch")
if ($remoteRef.ExitCode -eq 0) {
    $isAncestor = Invoke-Git -Arguments @('merge-base', '--is-ancestor', "$TargetRemote/$TargetBranch", 'HEAD')
    if ($isAncestor.ExitCode -ne 0) {
        Stop-Gate "history diverged: $TargetRemote/$TargetBranch is not an ancestor of HEAD." `
            "Rebase or merge onto $TargetRemote/$TargetBranch by hand, then re-run. Never force-push."
    }
    $ahead = Invoke-Git -Arguments @('rev-list', '--count', "$TargetRemote/$TargetBranch..HEAD")
    if ($ahead.ExitCode -eq 0) {
        $parsed = 0
        if ([int]::TryParse($ahead.Output, [ref] $parsed)) { $aheadCount = $parsed }
    }
    Write-Host "    commits to push: $aheadCount"
}
else {
    Write-Host "    $TargetRemote/$TargetBranch does not exist yet - first push"
}

# ---------------------------------------------------------------------------
# 6. Push. Never forced.
# ---------------------------------------------------------------------------
Write-Step "push $TargetRemote $TargetBranch"

$headResult = Invoke-Git -Arguments @('rev-parse', 'HEAD')
if ($headResult.ExitCode -ne 0) {
    Stop-Gate 'could not resolve HEAD.' 'Inspect git log manually.'
}
$headSha = $headResult.Output

if ($aheadCount -eq 0 -and $remoteRef.ExitCode -eq 0) {
    Write-Host "    $TargetRemote/$TargetBranch already contains $headSha - nothing to push"
}
else {
    $push = Invoke-Git -Arguments @('push', $TargetRemote, $TargetBranch)
    if ($push.ExitCode -ne 0) {
        Stop-Gate "git push $TargetRemote $TargetBranch was rejected: $($push.Output)" `
            'Resolve it by hand (pull --rebase, credentials, branch protection). DO NOT force-push.'
    }
    if ($push.Output) { Write-Host "    $($push.Output)" }
}

# ---------------------------------------------------------------------------
# 7. Verify the remote really points at HEAD, then report.
# ---------------------------------------------------------------------------
$verify = Invoke-Git -Arguments @('rev-parse', "$TargetRemote/$TargetBranch")
if ($verify.ExitCode -ne 0) {
    Stop-Gate "could not read $TargetRemote/$TargetBranch after pushing." 'Run git fetch, then inspect.'
}
if ($verify.Output -ne $headSha) {
    Stop-Gate "$TargetRemote/$TargetBranch ($($verify.Output)) does not match HEAD ($headSha)." `
        'Investigate before declaring the phase done.'
}

$finalSubject = (Invoke-Git -Arguments @('log', '-1', '--pretty=%s')).Output

Write-Host ''
Write-Host "PHASE $Phase DONE" -ForegroundColor Green
Write-Host '  build+tests : assembleDebug + testDebugUnitTest PASSED'
Write-Host '  tree        : committed'
Write-Host "  commit      : $headSha $finalSubject"
Write-Host "  pushed      : $TargetRemote/$TargetBranch -> $($remoteUrl.Output)"
Write-Host "  sha         : $headSha"

exit 0
