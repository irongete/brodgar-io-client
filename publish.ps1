<#
.SYNOPSIS
  Publish the Brodgar.io client on GitHub: build, zip, tag, push, release.

.DESCRIPTION
    .\publish.ps1 -Beta                the next beta:     v5 -> v5.1-beta, v5.1-beta -> v5.2-beta
    .\publish.ps1 -Release             the next release:  v5.3-beta -> v6, v5 -> v6
    .\publish.ps1 -Release -Version 6  that number

  A release is vN, which every launcher installs; a beta is vN.X-beta, the X-th since release N, which only
  the Beta channel installs; nothing published counts as release 0. The next one is counted from the newest
  release on GitHub and printed with the branch and the commit -- and, for a release after a beta, whether
  master still holds that beta's code -- then confirmed before the build (-Yes skips the question).

  Refuses a dirty tree, a branch other than master (-Branch), a version not above GitHub's newest and a tag
  that exists anywhere. Runs `ant -Dversion=<v> release`, tags v<v>, pushes the tag and then the branch,
  and creates the release with the zip. CI (.github/workflows/publish.yml) runs this same script on every
  push to master (-Beta) and from the Run workflow button (-Release), and skips a commit that carries a
  tag already. Needs git, ant and gh (`gh auth login`).

.PARAMETER Beta
  The next beta, vN.X-beta.
.PARAMETER Release
  The next release, vN.
.PARAMETER Version
  That number instead of the counted one: N, or N.X with -Beta.
.PARAMETER Notes
  A markdown file with the release notes; default: the commit subjects since the previous version.
.PARAMETER Message
  The release notes, inline.
.PARAMETER Branch
  The branch to publish from; master.
.PARAMETER Draft
  Create the release as a draft.
.PARAMETER NoPublish
  Build, zip and tag only.
.PARAMETER Yes
  Do not ask.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Beta,
    [switch]$Release,
    [Parameter(Position = 0)][string]$Version,
    [string]$Notes,
    [string]$Message,
    [string]$Branch = 'master',
    [switch]$Draft,
    [switch]$NoPublish,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$repo = 'irongete/brodgar-io-client'
$product = 'Brodgar.io client'
Set-Location $PSScriptRoot

function Run {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

# vN or vN.X-beta (the older vX.Y.Z tags parse too). Key sorts as the launcher does: number by number, a
# missing one as 0, a release above the beta of its number.
function Parse-Version {
    param([string]$Text)
    $m = [regex]::Match($Text, '^v?(\d+(?:\.\d+)*)(-beta)?$')
    if (-not $m.Success) { return $null }
    $numbers = @($m.Groups[1].Value -split '\.' | ForEach-Object { [int]$_ })
    $isBeta = $m.Groups[2].Success
    $padded = @(0, 0, 0, 0)
    for ($i = 0; $i -lt $numbers.Count -and $i -lt 4; $i++) { $padded[$i] = $numbers[$i] }
    return [pscustomobject]@{
        Numbers = $numbers; Release = $numbers[0]; Beta = $isBeta
        Number = $m.Groups[1].Value
        Key = '{0:D9}.{1:D9}.{2:D9}.{3:D9}.{4}' -f $padded[0], $padded[1], $padded[2], $padded[3], $(if ($isBeta) { 0 } else { 1 })
    }
}

# The non-draft releases on GitHub, newest first. (@tsv: no quotes in the argument, PowerShell 5.1 would not escape them.)
function Get-Published {
    $lines = & gh api "repos/$repo/releases?per_page=100" --jq '.[] | select(.draft | not) | [.tag_name, .prerelease, .published_at] | @tsv'
    if ($LASTEXITCODE -ne 0) { throw "could not list the releases of $repo (gh api failed)" }
    $published = @()
    foreach ($line in @($lines)) {
        if (-not $line) { continue }
        $tagName, $pre, $date = $line -split "`t"
        $v = Parse-Version $tagName
        if (-not $v) { throw "GitHub serves $tagName, which is not a version (N, or N.X-beta): name the version with -Version, and consider retagging that one" }
        if ($date.Length -gt 10) { $date = $date.Substring(0, 10) }
        $published += [pscustomobject]@{ Tag = $tagName; Version = $v; Prerelease = ($pre -eq 'true'); Date = $date }
    }
    return $published | Sort-Object { $_.Version.Key } -Descending
}

# --- checks -------------------------------------------------------------------------------------------------
if ($Beta -and $Release) { throw 'say -Beta or -Release, not both' }
if (-not ($Beta -or $Release)) { throw 'say -Beta (a pre-release, for the Beta channel) or -Release (for every launcher)' }
$channel = if ($Beta) { 'beta' } else { 'release' }
if ($Version) {
    $named = Parse-Version $Version
    if (-not $named -or $Version.StartsWith('v')) { throw "the version must be a number, N or N.X (the switch says whether it is a beta), not '$Version'" }
    if ($named.Beta -and $Release) { throw "'$Version' names a beta and -Release a release: say -Beta, or -Version $($named.Number)" }
}
if ($Notes -and $Message) { throw 'give -Notes or -Message, not both' }
if ($Notes -and -not (Test-Path $Notes)) { throw "notes file not found: $Notes" }
if (git status --porcelain) { throw 'the working tree is not clean: commit or stash first' }
$current = (git rev-parse --abbrev-ref HEAD).Trim()   # not $branch: PowerShell names are case-insensitive
if ($current -ne $Branch) { throw "you are on '$current', and a release is cut from '$Branch': check it out (or pass -Branch $current to mean it)" }
$needsGitHub = -not ($NoPublish -and $Version)        # a named version built and tagged only needs no GitHub
if ($needsGitHub) {
    gh auth status *> $null
    if ($LASTEXITCODE -ne 0) { throw 'gh is not logged in: run `gh auth login` first' }
}

# --- the version --------------------------------------------------------------------------------------------
$published = @()
$newest = $null
if ($needsGitHub) {
    $published = @(Get-Published)
    if ($published.Count -gt 0) { $newest = $published[0] }
}
if ($Version) {
    $number = $named.Number
} else {
    if ($current -ne 'master') { throw "on $current the next number is not counted, since GitHub's newest may be another line's: name it with -Version" }
    $n = if ($newest) { $newest.Version } else { Parse-Version '0' }   # nothing published: release 0
    if ($Beta) {
        $x = if ($n.Beta -and $n.Numbers.Count -ge 2) { $n.Numbers[1] + 1 } else { 1 }
        $number = '{0}.{1}' -f $n.Release, $x
    } else {
        $number = [string]($n.Release + 1)
    }
}
$Version = if ($Beta) { "$number-beta" } else { $number }
$tag = "v$Version"
$title = "$product $tag"
# a release after a beta: is it that beta's code?
$codeNote = $null
if ($Release -and $newest -and $newest.Version.Beta) {
    $betaCommit = git rev-parse -q --verify "refs/tags/$($newest.Tag)^{commit}"
    if ($betaCommit) {
        $ahead = [int](git rev-list --count "$betaCommit..HEAD")
        $behind = [int](git rev-list --count "HEAD..$betaCommit")
        $codeNote = if ($ahead -eq 0 -and $behind -eq 0) { "the same code as $($newest.Tag), now official" }
                    elseif ($behind -gt 0) { "NOT $($newest.Tag)'s code: that tag is not an ancestor of HEAD" }
                    else { "master has moved since $($newest.Tag) ($ahead commits): code no beta has run" }
    } else {
        $codeNote = "whether it is $($newest.Tag)'s code cannot be told: that tag is not in this clone"
    }
}
# the guards: above GitHub's newest (on master), and a tag that is nowhere yet
if ($newest -and $current -eq 'master' -and (Parse-Version $Version).Key -le $newest.Version.Key) {
    throw "$tag is not above $($newest.Tag), the newest on GitHub, and a launcher never installs a lower version: name one above it, or leave -Version out"
}
$asset = Join-Path 'build' "brodgar-io-client-$tag.zip"   # Join-Path: CI runs this on Linux
if (git tag -l $tag) { throw "the tag $tag already exists in this clone" }
if ($needsGitHub -and ($published | Where-Object { $_.Tag -eq $tag })) { throw "$tag is published on GitHub already" }
if (-not $NoPublish -and (git ls-remote --tags origin $tag)) { throw "the tag $tag already exists on origin" }

# --- the plan -----------------------------------------------------------------------------------------------
$short = (git rev-parse --short HEAD).Trim()
Write-Host ''
Write-Host ("  newest on GitHub:  " + $(if ($newest) { "$($newest.Tag) ($(if ($newest.Prerelease) { 'beta' } else { 'release' }), $($newest.Date))" } elseif ($needsGitHub) { 'nothing' } else { 'not asked (-NoPublish with -Version)' }))
Write-Host ("  this publishes:    $tag -- " + $(if ($Beta) { 'a BETA: a GitHub pre-release, installed by launchers on the Beta channel' } else { 'a RELEASE: a plain GitHub release, installed by every launcher' }))
if ($codeNote) { Write-Host "  code:              $codeNote" }
Write-Host ("  from:              $current @ $short" + $(if ($NoPublish) { '  (-NoPublish: built and tagged here, nothing pushed, no release)' } elseif ($Draft) { '  (-Draft: the release is created as a draft)' } else { '' }))
Write-Host ''
if (-not $Yes) {
    $answer = Read-Host 'Continue? [y/N]'
    if ($answer -notmatch '^[yY]') { Write-Host 'Nothing done.'; exit 1 }
}

# --- the notes ----------------------------------------------------------------------------------------------
if ($Notes) {
    $notesFile = (Resolve-Path $Notes).Path
} else {
    $notesFile = Join-Path ([IO.Path]::GetTempPath()) "brodgar-io-client-$Version-notes.md"
    if ($Message) {
        Set-Content -Path $notesFile -Value $Message -Encoding UTF8
    } else {
        # since the newest published version when its tag is here, else since the highest tag reachable
        $previous = $null
        if ($newest -and (git tag -l $newest.Tag)) { $previous = $newest.Tag }
        if (-not $previous) { $previous = git tag -l 'v*' --merged HEAD --sort=-v:refname | Select-Object -First 1 }
        if ($previous) {
            $log = @(git log --format='- %s' --max-count=200 "$previous..HEAD")
            if (-not $log) { throw "no commits since $previous to write notes from: give -Notes or -Message" }
            $count = [int](git rev-list --count "$previous..HEAD")
            if ($count -gt $log.Count) { $log += "- ... and $($count - $log.Count) more" }
        } else {
            $log = @('The first version.')   # not the whole history of the fork
        }
        Set-Content -Path $notesFile -Value ($log -join "`n") -Encoding UTF8
        Write-Host "Release notes$(if ($previous) { " (the commits since $previous)" }):"
        $log | ForEach-Object { Write-Host "  $_" }
    }
}

# --- build, from scratch ------------------------------------------------------------------------------------
Write-Host "Building $title ($channel) from $current ($short)..."
$classes = Join-Path 'build' 'classes'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
Run ant @("-Dversion=$Version", 'release')
if (-not (Test-Path $asset)) { throw "the build produced no $asset" }
Write-Host ("Asset: {0} ({1:N1} MB)" -f $asset, ((Get-Item $asset).Length / 1MB))

# --- tag, push, release -------------------------------------------------------------------------------------
Run git @('tag', '-a', $tag, '-m', $title)
if ($NoPublish) {
    Write-Host "Tagged $tag. Nothing pushed and no release created (-NoPublish)."
    exit 0
}
Run git @('push', 'origin', $tag)        # the tag first: CI skips a pushed commit that carries one
Run git @('push', 'origin', $current)
$create = @('release', 'create', $tag, $asset, '--repo', $repo, '--title', $title, '--notes-file', $notesFile)
if ($Beta) { $create += '--prerelease' }
if ($Draft) { $create += '--draft' }
Run gh $create
Write-Host "Published $title as $tag on the $channel channel."
