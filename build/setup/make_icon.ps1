# Builds build\setup\input\app.ico as a classic multi-size BMP-entry icon
# (16/32/48/64/128/256), universally accepted by Windows, WiX and jpackage.
Add-Type -AssemblyName System.Drawing

$outDir = Join-Path $PSScriptRoot 'input'
New-Item -ItemType Directory -Force $outDir | Out-Null

function New-IconFrame([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)

    # rounded square background (1/16 border inset)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $x0 = [Math]::Floor($size / 16); $w = $size - 2 * $x0
    $r = [Math]::Floor($size * 0.18)
    $path.AddArc($x0, $x0, $r, $r, 180, 90)
    $path.AddArc($x0 + $w - $r, $x0, $r, $r, 270, 90)
    $path.AddArc($x0 + $w - $r, $x0 + $w - $r, $r, $r, 0, 90)
    $path.AddArc($x0, $x0 + $w - $r, $r, $r, 90, 90)
    $path.CloseFigure()
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point(0, 0)),
        (New-Object System.Drawing.Point(0, $size)),
        [System.Drawing.Color]::FromArgb(255, 16, 66, 110),
        [System.Drawing.Color]::FromArgb(255, 41, 138, 196))
    $g.FillPath($bg, $path)

    # white "QFT" text, centered on the left two thirds
    $f = New-Object System.Drawing.Font('Segoe UI',
        [Math]::Floor($size * 0.30), [System.Drawing.FontStyle]::Bold)
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = [System.Drawing.StringAlignment]::Center
    $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = [System.Drawing.RectangleF]::new(
        [float]$x0, [float]($x0 - 1), [float]$w, [float]($size * 0.62))
    $g.DrawString('QFT', $f, [System.Drawing.Brushes]::White, $rect, $fmt)

    $f.Dispose(); $bg.Dispose(); $path.Dispose(); $g.Dispose()
    return $bmp
}

function Get-BgraBytes([System.Drawing.Bitmap]$bmp, [int]$size) {
    # bottom-up BGRA rows, the classic ICO XOR plane
    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $data = $bmp.LockBits($rect,
        [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = [Math]::Abs($data.Stride)
    $out = New-Object byte[] ($size * $size * 4)
    for ($y = 0; $y -lt $size; $y++) {
        $fromRow = [System.Runtime.InteropServices.Marshal]::Copy
        $src = $data.Scan0.ToInt64() + ($size - 1 - $y) * $data.Stride
        $buffer = New-Object byte[] ($size * 4)
        [System.Runtime.InteropServices.Marshal]::Copy([IntPtr]$src, $buffer, 0, $size * 4)
        for ($x = 0; $x -lt $size; $x++) {
            $s = $x * 4
            $d = ($y * $size + $x) * 4
            # ArgB buffer is B,G,R,A already
            $out[$d]     = $buffer[$s]
            $out[$d + 1] = $buffer[$s + 1]
            $out[$d + 2] = $buffer[$s + 2]
            $out[$d + 3] = $buffer[$s + 3]
        }
    }
    $bmp.UnlockBits($data)
    return ,$out
}

$sizes = @(16, 32, 48, 64, 128, 256)
$frames = @()
foreach ($s in $sizes) { $frames += (New-IconFrame $s) }

$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$sizes.Count)

$entries = @()
foreach ($i in 0..($sizes.Count - 1)) {
    $s = $sizes[$i]
    $xor = Get-BgraBytes $frames[$i] $s
    $andRow = [Math]::Ceiling($s / 32) * 4
    $and = New-Object byte[] ($andRow * $s)   # fully transparent mask (alpha rules)

    $header = New-Object byte[] 40
    [System.BitConverter]::GetBytes([UInt32]40).CopyTo($header, 0)                  # biSize
    [System.BitConverter]::GetBytes([Int32]$s).CopyTo($header, 4)                   # width
    [System.BitConverter]::GetBytes([Int32]($s * 2)).CopyTo($header, 8)             # height (XOR+AND)
    [System.BitConverter]::GetBytes([UInt16]1).CopyTo($header, 12)                  # planes
    [System.BitConverter]::GetBytes([UInt16]32).CopyTo($header, 14)                 # bpp
    [System.BitConverter]::GetBytes([UInt32]($xor.Length + $and.Length)).CopyTo($header, 20)

    $data = New-Object byte[] ($header.Length + $xor.Length + $and.Length)
    [System.Buffer]::BlockCopy($header, 0, $data, 0, $header.Length)
    [System.Buffer]::BlockCopy($xor, 0, $data, $header.Length, $xor.Length)
    [System.Buffer]::BlockCopy($and, 0, $data, $header.Length + $xor.Length, $and.Length)
    $entries += @{ W = [Math]::Min($s, 255); Size = $data.Length; Data = $data }
}

$offset = 6 + 16 * $sizes.Count
foreach ($e in $entries) {
    $bw.Write([byte]$e.W); $bw.Write([byte]$e.W); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$e.Size); $bw.Write([UInt32]$offset)
    $offset += $e.Size
}
foreach ($e in $entries) { $bw.Write($e.Data) }
$bw.Flush()
$icoPath = Join-Path $outDir 'app.ico'
[System.IO.File]::WriteAllBytes($icoPath, $ms.ToArray())
$bw.Dispose()
$frames | ForEach-Object { $_.Dispose() }
Write-Output ('icon ok: ' + $icoPath + ' (' + (Get-Item $icoPath).Length + ' bytes)')
