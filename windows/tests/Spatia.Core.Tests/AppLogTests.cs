using Spatia.Core.Logic;
using Xunit;

namespace Spatia.Core.Tests;

public class AppLogTests
{
    [Fact]
    public void Writes_levels_and_stages_and_never_throws()
    {
        var dir = Path.Combine(Path.GetTempPath(), "spatia-log-" + Guid.NewGuid());
        try
        {
            AppLog.Initialize(dir);
            AppLog.Info("startup", "hello");
            AppLog.Error("page player", new InvalidOperationException("boom"));
            var text = File.ReadAllText(AppLog.FilePath);
            Assert.Contains("INFO  [startup] Spatia", text);
            Assert.Contains("ERROR [page player] System.InvalidOperationException: boom", text);
        }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }

    [Fact]
    public void Rotates_when_too_large()
    {
        var dir = Path.Combine(Path.GetTempPath(), "spatia-log-" + Guid.NewGuid());
        try
        {
            AppLog.Initialize(dir);
            File.WriteAllText(AppLog.FilePath, new string('x', (int)AppLog.MaxBytes + 10));
            AppLog.Info("test", "after rotation");
            Assert.True(File.Exists(AppLog.FilePath + ".old"));
            Assert.True(new FileInfo(AppLog.FilePath).Length < 1000);
        }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }
}
