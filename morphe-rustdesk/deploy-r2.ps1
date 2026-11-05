[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $BundlePath,

    [Parameter(Mandatory)]
    [uri] $PublicBaseUrl,

    [ValidatePattern('^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$')]
    [string] $Bucket,

    [string] $Prefix = '',

    [uri] $SourceUrl,

    [string] $Description = 'Morphe patches for RustDesk on Android.',

    [string] $StageDirectory
)

$ErrorActionPreference = 'Stop'

if (!$PublicBaseUrl.IsAbsoluteUri -or $PublicBaseUrl.Scheme -ne 'https' -or
    $PublicBaseUrl.Query -or $PublicBaseUrl.Fragment -or $PublicBaseUrl.UserInfo) {
    throw 'PublicBaseUrl must be an HTTPS URL without credentials, a query, or a fragment.'
}
if ($SourceUrl) {
    if (!$SourceUrl.IsAbsoluteUri -or $SourceUrl.Scheme -ne 'https' -or $SourceUrl.UserInfo) {
        throw 'SourceUrl must be a public HTTPS URL.'
    }
    $Description += "`n`n[Source code]($($SourceUrl.AbsoluteUri))"
}
if ($Prefix -and $Prefix -notmatch '^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$') {
    throw 'Prefix must contain only letters, digits, periods, underscores, and hyphens separated by slashes.'
}
if (!$StageDirectory -and !$Bucket) {
    throw 'Supply Bucket for publication or StageDirectory for local staging.'
}

$bundle = Get-Item -LiteralPath $BundlePath
if ($bundle.PSIsContainer -or $bundle.Extension -ne '.mpp') {
    throw 'BundlePath must be a Morphe .mpp file.'
}

function Get-BundleMetadata([string] $Path, [datetime] $FallbackTimestamp) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (!$entry -or !$archive.GetEntry('classes.dex')) {
            throw 'The .mpp must contain a JAR manifest and Android patch bytecode.'
        }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try {
            $manifest = $reader.ReadToEnd() -replace '\r\n?', "`n"
        } finally {
            $reader.Dispose()
        }
        $mainAttributes = (($manifest -split "`n`n", 2)[0]) -replace "`n ", ''
        if ($mainAttributes -cnotmatch '(?m)^Version: ([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?)$') {
            throw 'The .mpp manifest needs a three-part Version, for example 1.0.0.'
        }
        $version = $Matches[1]
        $timestamp = $FallbackTimestamp
        if ($mainAttributes -cmatch '(?m)^Timestamp: ([0-9]{1,13})$') {
            $timestamp = [DateTimeOffset]::FromUnixTimeMilliseconds([long] $Matches[1]).UtcDateTime
        }
        return @{ Version = $version; Timestamp = $timestamp }
    } finally {
        $archive.Dispose()
    }
}

function Publish-R2Object([string] $Key, [string] $Path, [string] $ContentType, [string] $CacheControl) {
    $arguments = @('r2', 'objects', 'put', $Key, '--bucket-name', $Bucket, '--file', $Path, '--content-type', $ContentType)
    if ($cfHelp -match '--cache-control') {
        $arguments += @('--cache-control', $CacheControl)
    }
    & $cfCommand @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "R2 upload failed for $Key (exit $LASTEXITCODE)."
    }
}

function Get-PublicIndex {
    try {
        return Invoke-RestMethod -Uri $indexUrl -UseBasicParsing -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 30
    } catch {
        if ($_.Exception.Response -and [int] $_.Exception.Response.StatusCode -eq 404) {
            return $null
        }
        throw
    }
}

$workDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('rustdesk-morphe-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $workDirectory
$localBundle = Join-Path $workDirectory 'bundle.mpp'
$localIndex = Join-Path $workDirectory 'patches-bundle.json'
$downloadedBundle = Join-Path $workDirectory 'download.mpp'

try {
    Copy-Item -LiteralPath $bundle.FullName -Destination $localBundle
    $bundleMetadata = Get-BundleMetadata $localBundle $bundle.LastWriteTimeUtc
    $version = $bundleMetadata.Version
    $digest = (Get-FileHash -LiteralPath $localBundle -Algorithm SHA256).Hash.ToLowerInvariant()
    # A retry or concurrent upload cannot change the content served at an existing bundle URL.
    $bundleName = "rustdesk-patches-$version-$digest.mpp"
    $keyPrefix = if ($Prefix) { "$Prefix/" } else { '' }
    $bundleKey = "$keyPrefix$bundleName"
    $indexKey = "${keyPrefix}patches-bundle.json"
    $publicRoot = $PublicBaseUrl.AbsoluteUri.TrimEnd('/')
    $bundleUrl = "$publicRoot/$bundleKey"
    $indexUrl = "$publicRoot/$indexKey"

    $index = [ordered] @{
        created_at = $bundleMetadata.Timestamp.ToString("yyyy-MM-dd'T'HH:mm:ss", [Globalization.CultureInfo]::InvariantCulture)
        description = $Description
        download_url = $bundleUrl
        signature_download_url = ''
        version = $version
    }
    [System.IO.File]::WriteAllText($localIndex, ($index | ConvertTo-Json) + "`n", [System.Text.UTF8Encoding]::new($false))

    if ($StageDirectory) {
        $staged = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($StageDirectory)
        if ($Prefix) { $staged = Join-Path $staged $Prefix }
        $null = New-Item -ItemType Directory -Path $staged -Force
        Copy-Item -LiteralPath $localBundle -Destination (Join-Path $staged $bundleName)
        Copy-Item -LiteralPath $localIndex -Destination (Join-Path $staged 'patches-bundle.json')
        Write-Output $indexUrl
        return
    }

    $cfCommand = Get-Command cf, cloudflare -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$cfCommand) { throw 'Install and authenticate the Cloudflare cf CLI to publish to R2.' }
    $cfHelp = (& $cfCommand r2 objects put --help | Out-String)
    if ($LASTEXITCODE -ne 0 -or $cfHelp -notmatch '--bucket-name' -or $cfHelp -notmatch '--file') {
        throw 'Installed cf does not support R2 object upload. Update the Cloudflare cf CLI.'
    }

    $publishedIndex = Get-PublicIndex
    if ($null -ne $publishedIndex -and (!$publishedIndex.version -or !$publishedIndex.download_url)) {
        throw "The existing URL does not serve a Morphe release index: $indexUrl"
    }
    if ($publishedIndex.version -ceq $version -and $publishedIndex.download_url -cne $bundleUrl) {
        throw "Bundle version $version is already published with a different artifact. Bump the bundle version."
    }

    Publish-R2Object $bundleKey $localBundle 'application/octet-stream' 'public, max-age=31536000, immutable'
    Invoke-WebRequest -Uri $bundleUrl -UseBasicParsing -OutFile $downloadedBundle -TimeoutSec 60
    if ((Get-FileHash -LiteralPath $downloadedBundle -Algorithm SHA256).Hash -ne $digest) {
        throw "The public bundle does not match the local .mpp: $bundleUrl"
    }

    Publish-R2Object $indexKey $localIndex 'application/json' 'no-cache'
    $publishedIndex = Get-PublicIndex
    if ($publishedIndex.version -cne $version -or $publishedIndex.download_url -cne $bundleUrl -or
        $publishedIndex.description -cne $Description) {
        throw "The public index does not match this release. Check the public URL and cache rules: $indexUrl"
    }
    Write-Output $indexUrl
} finally {
    Remove-Item -LiteralPath $localBundle, $localIndex, $downloadedBundle -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $workDirectory
}
