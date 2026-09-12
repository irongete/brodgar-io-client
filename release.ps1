<#
.SYNOPSIS
  Cut a release of the Brodgar.io client: build, zip, tag, push, publish on GitHub.

.DESCRIPTION
  One command does the whole release:

    .\release.ps1 0.1.0-beta.1 -Notes notes.md      a beta: a GitHub pre-release, notes from a markdown file
    .\release.ps1 0.1.0 -Message "The beta is over"  a release: a plain GitHub release, notes inline
    .\release.ps1 0.1.1                              release notes = the commit subjects since the last v* tag

  It refuses to run on a dirty tree or an existing tag, compiles from scratch (build/classes is wiped, so a
  symbol that moved between files cannot hide), runs `ant -Dversion=<version> release-zip` -- dist/ rebuilt,
  the addons etc/release-addons names copied from the sibling addons repository, and the zip made without
  savedata/ -- tags HEAD as v<version>, pushes the branch and the tag, and creates the GitHub release with the
  zip as its asset.

  The channel is the version's: a suffix (0.1.0-beta.1, 0.2.0-rc.2) makes a BETA, published as a GitHub
  pre-release, which only a launcher on its beta channel installs; a plain x.y.z makes a RELEASE, a plain
  GitHub release, which every launcher installs. -Channel says otherwise when it must.

  Needs git, ant and gh (logged in: `gh auth login`) on the PATH.

.PARAMETER Version
  1.2.3 or 1.2.3-beta.1: the tag is v<Version>, the asset brodgar-io-client-<Version>.zip.
.PARAMETER Notes
  A markdown file with the release notes.
.PARAMETER Message
  The release notes, inline.
.PARAMETER Channel
  release or beta; by default the version decides (a suffix is a beta).
.PARAMETER Draft
  Create the release as a draft, to be published by hand on GitHub.
.PARAMETER NoPublish
  Build, zip and tag only: nothing is pushed and no release is created.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Version,
    [string]$Notes,
    [string]$Message,
    [ValidateSet('release', 'beta')][string]$Channel,
    [switch]$Draft,
    [switch]$NoPublish
)

$ErrorActionPreference = 'Stop'
$repo = 'irongete/brodgar-io-client'
$title = "Brodgar.io client $Version"
$tag = "v$Version"
$asset = "build\brodgar-io-client-$Version.zip"
Set-Location $PSScriptRoot
if (-not $Channel) { $Channel = if ($Version -match '-') { 'beta' } else { 'release' } }

function Run {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

# --- checks -------------------------------------------------------------------------------------------------
if ($Version -notmatch '^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$') { throw "the version must look like 1.2.3 or 1.2.3-beta.1, not '$Version'" }
if ($Notes -and $Message) { throw 'give -Notes or -Message, not both' }
if ($Notes -and -not (Test-Path $Notes)) { throw "notes file not found: $Notes" }
if (git status --porcelain) { throw 'the working tree is not clean: commit or stash first' }
if (git tag -l $tag) { throw "the tag $tag already exists" }
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if (-not $NoPublish) {
    gh auth status *> $null
    if ($LASTEXITCODE -ne 0) { throw 'gh is not logged in: run `gh auth login` first' }
    if (git ls-remote --tags origin $tag) { throw "the tag $tag already exists on origin" }
}

# --- the notes ----------------------------------------------------------------------------------------------
if ($Notes) {
    $notesFile = (Resolve-Path $Notes).Path
} else {
    $notesFile = Join-Path $env:TEMP "brodgar-io-client-$Version-notes.md"
    if ($Message) {
        Set-Content -Path $notesFile -Value $Message -Encoding UTF8
    } else {
        $previous = (git describe --tags --abbrev=0 --match 'v*' 2>$null)
        $range = if ($previous) { "$previous..HEAD" } else { 'HEAD' }
        $log = git log --format='- %s' $range
        if (-not $log) { throw "no commits since $previous to write notes from: give -Notes or -Message" }
        Set-Content -Path $notesFile -Value $log -Encoding UTF8
        Write-Host "Release notes (the commits since $(if ($previous) { $previous } else { 'the beginning' })):"
        $log | ForEach-Object { Write-Host "  $_" }
    }
}

# --- build: from scratch, then dist/ and the zip -------------------------------------------------------------
Write-Host "Building $title ($Channel) from $branch ($((git rev-parse --short HEAD).Trim()))..."
if (Test-Path build\classes) { Remove-Item -Recurse -Force build\classes }
Run ant @("-Dversion=$Version", 'release-zip')
if (-not (Test-Path $asset)) { throw "the build produced no $asset" }
Write-Host ("Asset: {0} ({1:N1} MB)" -f $asset, ((Get-Item $asset).Length / 1MB))

# --- tag ----------------------------------------------------------------------------------------------------
Run git @('tag', '-a', $tag, '-m', $title)
if ($NoPublish) {
    Write-Host "Tagged $tag. Nothing pushed and no release created (-NoPublish)."
    exit 0
}

# --- push and publish ---------------------------------------------------------------------------------------
Run git @('push', 'origin', $branch)
Run git @('push', 'origin', $tag)
$create = @('release', 'create', $tag, $asset, '--repo', $repo, '--title', $title, '--notes-file', $notesFile)
if ($Channel -eq 'beta') { $create += '--prerelease' }
if ($Draft) { $create += '--draft' }
Run gh $create
Write-Host "Released $title as $tag on the $Channel channel."
