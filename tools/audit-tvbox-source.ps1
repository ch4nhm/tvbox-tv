<#
.SYNOPSIS
  Audit a TVBox configuration source before building a custom client.

.DESCRIPTION
  Reads a TVBox config from a URL or local JSON file (supports Base64 encoded
  configs), then reports site types, spider kinds, parsers, live sources and
  other features that affect implementation scope.

.PARAMETER Source
  URL or local path. If omitted, the first *.json file in .\source is used.

.PARAMETER FollowFirst
  When the config is a depot (has "urls"), load the first depot entry too.

.PARAMETER OutFile
  Optional Markdown output path. The report is also printed to stdout.

.EXAMPLE
  .\tools\audit-tvbox-source.ps1 -Source https://example.com/tvbox.json

.EXAMPLE
  .\tools\audit-tvbox-source.ps1 -Source .\source\source.json -FollowFirst -OutFile .\docs\source-audit.md
#>
param(
    [string]$Source,
    [switch]$FollowFirst,
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'

function Resolve-SourceText {
    param([string]$Location)

    if ($Location -match '^https?://') {
        $response = Invoke-WebRequest -Uri $Location -UseBasicParsing -TimeoutSec 40
        return [string]$response.Content
    }

    if (-not (Test-Path -LiteralPath $Location)) {
        throw "Source not found: $Location"
    }

    return Get-Content -LiteralPath $Location -Raw
}

function Try-Base64Decode {
    param([string]$Text)

    $compact = ($Text -replace '\s', '')
    if ($compact.Length -lt 8) { return $null }

    $normalized = $compact.Replace('-', '+').Replace('_', '/')
    $padding = (4 - ($normalized.Length % 4)) % 4
    if ($padding -gt 0) { $normalized += ('=' * $padding) }

    try {
        $bytes = [Convert]::FromBase64String($normalized)
        if ($bytes.Length -lt 2) { return $null }
        $decoded = [Text.Encoding]::UTF8.GetString($bytes)
        if ($decoded.TrimStart().StartsWith('{') -or $decoded.TrimStart().StartsWith('[')) {
            return $decoded
        }
    } catch {
        return $null
    }

    return $null
}

function ConvertTo-ConfigObject {
    param([string]$Text)

    $trimmed = $Text.Trim().TrimStart([char]0xFEFF)
    if (-not ($trimmed.StartsWith('{') -or $trimmed.StartsWith('['))) {
        $decoded = Try-Base64Decode $trimmed
        if ($null -eq $decoded) {
            throw 'Content is neither JSON nor Base64-encoded JSON.'
        }
        $trimmed = $decoded.Trim().TrimStart([char]0xFEFF)
    }

    return $trimmed | ConvertFrom-Json -Depth 100
}

function Get-SpiderKind {
    param([string]$Api)

    if ([string]::IsNullOrWhiteSpace($Api)) { return 'empty' }
    if ($Api.StartsWith('csp_')) { return 'java-jar' }
    if ($Api -match '\.js(\?|$)') { return 'quickjs' }
    if ($Api -match '\.py(\?|$)') { return 'python' }
    if ($Api.StartsWith('node:')) { return 'nodejs' }
    if ($Api -match '^https?://') { return 'http' }
    return 'other'
}

function Resolve-DepotUrl {
    param([string]$Base, [string]$Child)

    if ($Child -match '^https?://') { return $Child }
    if ($Base -match '^https?://') {
        return ([Uri]::new([Uri]$Base, $Child)).AbsoluteUri
    }
    return (Join-Path (Split-Path -Parent $Base) $Child)
}

function Get-Count {
    param($Value)

    return @($Value | Where-Object { $null -ne $_ }).Count
}

function Write-Summary {
    param(
        [string]$Label,
        [object]$Config,
        [string]$Location
    )

    $sites = @($Config.sites | Where-Object { $null -ne $_ })
    $types = @{}
    $spiderKinds = @{}
    $siteJarOverrides = 0
    $searchable = 0

    foreach ($site in $sites) {
        $siteType = if ($null -eq $site.type) { 0 } else { [int]$site.type }
        $types[$siteType] = 1 + ($types[$siteType] -as [int])

        $kind = Get-SpiderKind ([string]$site.api)
        $spiderKinds[$kind] = 1 + ($spiderKinds[$kind] -as [int])

        if (-not [string]::IsNullOrWhiteSpace([string]$site.jar)) { $siteJarOverrides++ }
        if ($null -eq $site.searchable -or [int]$site.searchable -ne 0) { $searchable++ }
    }

    $parseTypes = @{}
    foreach ($parse in @($Config.parses | Where-Object { $null -ne $_ })) {
        $parseType = if ($null -eq $parse.type) { 0 } else { [int]$parse.type }
        $parseTypes[$parseType] = 1 + ($parseTypes[$parseType] -as [int])
    }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# TVBox 源审计：$Label")
    $lines.Add('')
    $lines.Add("源地址：``$Location``")
    $lines.Add('')
    $lines.Add('## 总览')
    $lines.Add('')
    $lines.Add('| 项目 | 数量/状态 |')
    $lines.Add('|---|---:|')
    $lines.Add("| sites | $($sites.Count) |")
    $lines.Add("| parses | $(Get-Count $Config.parses) |")
    $lines.Add("| lives | $(Get-Count $Config.lives) |")
    $lines.Add("| flags | $(Get-Count $Config.flags) |")
    $lines.Add("| headers | $(Get-Count $Config.headers) |")
    $lines.Add("| proxy | $(Get-Count $Config.proxy) |")
    $lines.Add("| rules | $(Get-Count $Config.rules) |")
    $lines.Add("| ads | $(Get-Count $Config.ads) |")
    $lines.Add("| doh | $(Get-Count $Config.doh) |")
    $lines.Add("| hosts | $(Get-Count $Config.hosts) |")
    $lines.Add("| 全局 spider | $(if ([string]::IsNullOrWhiteSpace([string]$Config.spider)) { '无' } else { '有' }) |")
    $lines.Add("| 站点自定义 jar | $siteJarOverrides |")
    $lines.Add("| 可搜索站点 | $searchable / $($sites.Count) |")
    $lines.Add('')
    $lines.Add('## 站点类型')
    $lines.Add('')
    $lines.Add('| type | 含义 | 数量 |')
    $lines.Add('|---:|---|---:|')
    $typeLabels = @{ 0 = 'XML HTTP'; 1 = 'JSON HTTP'; 3 = 'Spider'; 4 = '扩展 JSON HTTP' }
    foreach ($key in ($types.Keys | Sort-Object)) {
        $label = if ($typeLabels.ContainsKey([int]$key)) { $typeLabels[[int]$key] } else { '未知' }
        $lines.Add("| $key | $label | $($types[$key]) |")
    }
    if ($types.Count -eq 0) { $lines.Add('| - | 无站点 | 0 |') }
    $lines.Add('')
    $lines.Add('## Spider 类型')
    $lines.Add('')
    $lines.Add('| api 类型 | 数量 | 影响 |')
    $lines.Add('|---|---:|---|')
    $spiderImpact = @{
        'http' = '直接 HTTP，最易实现'
        'java-jar' = '需要 DexClassLoader + CatVod Spider ABI'
        'quickjs' = '需要 QuickJS 运行时'
        'python' = '需要 Python 运行时（Chaquopy）'
        'nodejs' = '需要 Node.js 运行时'
        'empty' = '空 api，需检查配置'
        'other' = '未知类型，需人工确认'
    }
    foreach ($key in ($spiderKinds.Keys | Sort-Object)) {
        $lines.Add("| $key | $($spiderKinds[$key]) | $($spiderImpact[$key]) |")
    }
    $lines.Add('')
    $lines.Add('## parses 类型')
    $lines.Add('')
    $lines.Add('| type | 含义 | 数量 |')
    $lines.Add('|---:|---|---:|')
    $parseLabels = @{ 0 = 'WebView'; 1 = 'JSON API'; 2 = 'JAR JSON'; 3 = 'JAR 混合'; 4 = '并行尝试' }
    foreach ($key in ($parseTypes.Keys | Sort-Object)) {
        $label = if ($parseLabels.ContainsKey([int]$key)) { $parseLabels[[int]$key] } else { '未知' }
        $lines.Add("| $key | $label | $($parseTypes[$key]) |")
    }
    if ($parseTypes.Count -eq 0) { $lines.Add('| - | 无解析器 | 0 |') }
    $lines.Add('')

    $spiderSites = @($sites | Where-Object { (Get-SpiderKind ([string]$_.api)) -ne 'http' })
    if ($spiderSites.Count -gt 0) {
        $lines.Add('## 非 HTTP 站点明细')
        $lines.Add('')
        $lines.Add('| key | name | type | api | jar |')
        $lines.Add('|---|---|---:|---|---|')
        foreach ($site in $spiderSites) {
            $lines.Add("| $($site.key) | $($site.name) | $($site.type) | ``$($site.api)`` | $($site.jar) |")
        }
        $lines.Add('')
    }

    $lines.Add('## 结论建议')
    $lines.Add('')
    $nonHttp = $spiderSites.Count
    if ($sites.Count -eq 0) {
        $lines.Add('- 配置里没有 sites，无法评估。')
    } elseif ($nonHttp -eq 0) {
        $lines.Add('- 站点全部是 HTTP API，可以直接用新 UI 从零实现，不需要 Spider 运行时。')
    } else {
        $lines.Add("- 有 $nonHttp 个站点依赖 Spider/JS/Python/Node，需要先确认 ABI 与运行时方案。")
        if ($spiderKinds.ContainsKey('java-jar')) {
            $lines.Add('- 存在 Java JAR 站点：建议复用 `catvod` 的 `Spider` ABI，并准备回归测试集。')
        }
        if ($spiderKinds.ContainsKey('quickjs') -or $spiderKinds.ContainsKey('python') -or $spiderKinds.ContainsKey('nodejs')) {
            $lines.Add('- 存在脚本运行时站点：需要评估 QuickJS/Chaquopy/Node 嵌入或自建运行时 APK。')
        }
    }
    $lines.Add('')

    return ($lines -join [Environment]::NewLine)
}

if ([string]::IsNullOrWhiteSpace($Source)) {
    $candidate = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '..\source') -Filter '*.json' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $candidate) {
        throw 'No source given and no *.json file found in .\source.'
    }
    $Source = $candidate.FullName
}

$text = Resolve-SourceText -Location $Source
$config = ConvertTo-ConfigObject -Text $text

$report = New-Object System.Collections.Generic.List[string]
$report.Add((Write-Summary -Label '主配置' -Config $config -Location $Source))

if ($FollowFirst -and $null -ne $config.urls -and @($config.urls).Count -gt 0) {
    $first = @($config.urls)[0]
    $depotUrl = Resolve-DepotUrl -Base $Source -Child ([string]$first.url)
    $depotText = Resolve-SourceText -Location $depotUrl
    $depotConfig = ConvertTo-ConfigObject -Text $depotText
    $report.Add((Write-Summary -Label "首个仓库：$($first.name)" -Config $depotConfig -Location $depotUrl))
}

$result = $report -join ([Environment]::NewLine + [Environment]::NewLine)
$result

if (-not [string]::IsNullOrWhiteSpace($OutFile)) {
    $parent = Split-Path -Parent $OutFile
    if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Set-Content -LiteralPath $OutFile -Value $result -Encoding utf8
    Write-Host "Report written to $OutFile"
}
