Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "app\src\main\res"

$sizes = @{
    "mipmap-mdpi"    = 48
    "mipmap-hdpi"    = 72
    "mipmap-xhdpi"   = 96
    "mipmap-xxhdpi"  = 144
    "mipmap-xxxhdpi" = 192
}

function New-RoundedRectPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

foreach ($entry in $sizes.GetEnumerator()) {
    $size = [int]$entry.Value
    $dir = Join-Path $res $entry.Key
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $inset = [float]($size * 0.04)
    $radius = [float]($size * 0.24)
    $path = New-RoundedRectPath $inset $inset ($size - 2 * $inset) ($size - 2 * $inset) $radius
    $rect = New-Object System.Drawing.RectangleF(0, 0, $size, $size)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.Color]::FromArgb(255, 59, 130, 246),
        [System.Drawing.Color]::FromArgb(255, 139, 92, 246),
        45.0
    )
    $g.FillPath($brush, $path)
    $brush.Dispose()
    $path.Dispose()

    # 白色对勾
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, ([float]($size * 0.085)))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $points = @(
        (New-Object System.Drawing.PointF(([float]($size * 0.27)), ([float]($size * 0.52)))),
        (New-Object System.Drawing.PointF(([float]($size * 0.43)), ([float]($size * 0.68)))),
        (New-Object System.Drawing.PointF(([float]($size * 0.75)), ([float]($size * 0.34))))
    )
    $g.DrawLines($pen, $points)
    $pen.Dispose()

    # 右上角提示点（AI / 提醒的视觉暗示）
    $dot = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 251, 191, 36))
    $dotSize = [float]($size * 0.11)
    $g.FillEllipse($dot, ([float]($size * 0.72)), ([float]($size * 0.16)), $dotSize, $dotSize)
    $dot.Dispose()

    $bmp.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $g.Dispose()
}

Write-Output "icons generated"
