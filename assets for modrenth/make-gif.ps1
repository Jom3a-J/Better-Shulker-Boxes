<#
.SYNOPSIS
  Turn a screen recording into a Modrinth-ready GIF under 1 MiB.

.EXAMPLE
  .\make-gif.ps1 -In raw\modern-tooltip.mkv -Out "mod gifs\optimized-under-1MiB\full-and-compact-tooltip.gif" -Start 3.2 -Duration 6

.EXAMPLE
  # crop to the tooltip region first (w:h:x:y in source pixels)
  .\make-gif.ps1 -In raw\restock.mkv -Out restock-and-deposit.gif -Crop 1200:675:360:200
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $In,
    [Parameter(Mandatory = $true)] [string] $Out,
    [double] $Start = 0,
    [double] $Duration = 0,      # 0 = to end of clip
    [string] $Crop = "",         # "w:h:x:y" in source pixels
    [int]    $Width = 480,       # matches the existing feature GIFs
    [double] $Fps = 8,
    [int]    $MaxBytes = 1048576,
    [ValidateSet("lanczos", "bicubic", "neighbor")] [string] $Scaler = "lanczos"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    throw "ffmpeg is not on PATH."
}
if (-not (Test-Path $In)) { throw "Input not found: $In" }

$outDir = Split-Path -Parent $Out
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Force $outDir | Out-Null }

$palette = Join-Path $env:TEMP "bsb-palette-$PID.png"

# Each attempt trades quality for size, in the order that hurts least:
# fewer colours first, then frame rate, then resolution.
$attempts = @(
    @{ Colors = 256; Fps = $Fps;       Width = $Width },
    @{ Colors = 160; Fps = $Fps;       Width = $Width },
    @{ Colors = 128; Fps = $Fps;       Width = $Width },
    @{ Colors = 128; Fps = $Fps * 0.75; Width = $Width },
    @{ Colors =  96; Fps = $Fps * 0.75; Width = $Width },
    @{ Colors =  96; Fps = $Fps * 0.75; Width = [int]($Width * 0.875) },
    @{ Colors =  64; Fps = $Fps * 0.625; Width = [int]($Width * 0.875) },
    @{ Colors =  64; Fps = $Fps * 0.625; Width = [int]($Width * 0.75) }
)

function Build-Filter([double] $f, [int] $w) {
    $parts = @()
    if ($Crop) { $parts += "crop=$Crop" }
    $parts += "fps=$f"
    $parts += "scale=${w}:-2:flags=$Scaler"
    return ($parts -join ",")
}

$trim = @()
if ($Start -gt 0)    { $trim += @("-ss", $Start) }
if ($Duration -gt 0) { $trim += @("-t",  $Duration) }

$made = $false
foreach ($a in $attempts) {
    $filter = Build-Filter $a.Fps $a.Width

    & ffmpeg -hide_banner -loglevel error -y @trim -i $In `
        -vf "$filter,palettegen=max_colors=$($a.Colors):stats_mode=diff" $palette
    if ($LASTEXITCODE -ne 0) { throw "palettegen failed" }

    & ffmpeg -hide_banner -loglevel error -y @trim -i $In -i $palette `
        -lavfi "$filter [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" `
        -loop 0 $Out
    if ($LASTEXITCODE -ne 0) { throw "paletteuse failed" }

    $size = (Get-Item $Out).Length
    $kib  = [math]::Round($size / 1KB, 1)
    Write-Host ("{0,4} colors  {1,5:N2} fps  {2,4}px  ->  {3} KiB" -f $a.Colors, $a.Fps, $a.Width, $kib)

    if ($size -le $MaxBytes) { $made = $true; break }
}

if (Test-Path $palette) { Remove-Item $palette -Force }

if ($made) {
    Write-Host "OK  $Out"
} else {
    Write-Warning "Still over $([math]::Round($MaxBytes / 1KB)) KiB. Shorten the clip (-Duration) or crop tighter."
}
