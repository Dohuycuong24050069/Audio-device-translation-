$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add('http://localhost:8080/')
$listener.Start()
Write-Host "Server started at http://localhost:8080/"

$baseDir = "C:\Users\Admin\.gemini\antigravity-ide\scratch\realtime-translator\web-simulator"

while ($listener.IsListening) {
    try {
        $context = $listener.GetContext()
        $request = $context.Request
        $response = $context.Response

        $path = $request.Url.LocalPath.TrimStart('/')
        if ($path -eq '' -or $path -eq '/') {
            $path = 'index.html'
        }

        $fullPath = Join-Path $baseDir $path
        if (Test-Path $fullPath -PathType Leaf) {
            $bytes = [System.IO.File]::ReadAllBytes($fullPath)
            if ($fullPath.EndsWith('.html')) {
                $response.ContentType = 'text/html; charset=utf-8'
            } elseif ($fullPath.EndsWith('.css')) {
                $response.ContentType = 'text/css'
            } elseif ($fullPath.EndsWith('.js')) {
                $response.ContentType = 'application/javascript'
            }
            $response.ContentLength64 = $bytes.Length
            $response.OutputStream.Write($bytes, 0, $bytes.Length)
        } else {
            $response.StatusCode = 404
        }
        $response.OutputStream.Close()
    } catch {
        Write-Host "Error: $_"
    }
}
