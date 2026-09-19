Add-Type -AssemblyName System.Drawing

$srcPath = Join-Path $PSScriptRoot "web-simulator\logo.jpg"
$resDir = Join-Path $PSScriptRoot "android-app\app\src\main\res"

$sizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

$img = [System.Drawing.Image]::FromFile($srcPath)

foreach ($folder in $sizes.Keys) {
    $dir = Join-Path $resDir $folder
    if (!(Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir | Out-Null
    }
    
    $sz = $sizes[$folder]
    $bmp = New-Object System.Drawing.Bitmap $sz, $sz
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.DrawImage($img, 0, 0, $sz, $sz)
    $g.Dispose()
    
    $outPath = Join-Path $dir "ic_launcher.png"
    $outRound = Join-Path $dir "ic_launcher_round.png"
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save($outRound, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "Generated $folder icon: ${sz}x${sz}"
}

# Also generate a large 512x512 adaptive foreground
$fgBmp = New-Object System.Drawing.Bitmap 432, 432
$fgG = [System.Drawing.Graphics]::FromImage($fgBmp)
$fgG.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$fgG.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$fgG.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$fgG.DrawImage($img, 36, 36, 360, 360)
$fgG.Dispose()
$fgPath = Join-Path $resDir "drawable\ic_launcher_foreground.png"
$fgBmp.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)
$fgBmp.Dispose()
Write-Host "Generated drawable\ic_launcher_foreground.png"

$img.Dispose()
Write-Host "All icons generated successfully!"
