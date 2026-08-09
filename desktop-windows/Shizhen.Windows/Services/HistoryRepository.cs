using Microsoft.Data.Sqlite;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services;

public sealed class HistoryRepository(DiagnosticLogger logger)
{
    private string ConnectionString => new SqliteConnectionStringBuilder
    {
        DataSource = AppPaths.DatabasePath,
        Mode = SqliteOpenMode.ReadWriteCreate,
        Cache = SqliteCacheMode.Shared
    }.ToString();

    public async Task InitializeAsync()
    {
        await using var connection = new SqliteConnection(ConnectionString);
        await connection.OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                operation_type TEXT NOT NULL,
                source TEXT NOT NULL,
                output_path TEXT,
                status INTEGER NOT NULL,
                error_reason TEXT,
                details TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_history_created_at ON history(created_at DESC);
            UPDATE history SET status = $interrupted, error_reason = COALESCE(error_reason, '应用上次退出时任务尚未完成。')
            WHERE status IN ($waiting, $running);
            """;
        command.Parameters.AddWithValue("$interrupted", (int)OperationStatus.Interrupted);
        command.Parameters.AddWithValue("$waiting", (int)OperationStatus.Waiting);
        command.Parameters.AddWithValue("$running", (int)OperationStatus.Running);
        await command.ExecuteNonQueryAsync();
    }

    public async Task<long> AddAsync(
        string operationType,
        string source,
        OperationStatus status,
        string? outputPath = null,
        string? errorReason = null,
        string? details = null)
    {
        await using var connection = new SqliteConnection(ConnectionString);
        await connection.OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO history(created_at, operation_type, source, output_path, status, error_reason, details)
            VALUES($created_at, $operation_type, $source, $output_path, $status, $error_reason, $details);
            SELECT last_insert_rowid();
            """;
        command.Parameters.AddWithValue("$created_at", DateTimeOffset.Now.ToString("O"));
        command.Parameters.AddWithValue("$operation_type", operationType);
        command.Parameters.AddWithValue("$source", source);
        command.Parameters.AddWithValue("$output_path", (object?)outputPath ?? DBNull.Value);
        command.Parameters.AddWithValue("$status", (int)status);
        command.Parameters.AddWithValue("$error_reason", (object?)errorReason ?? DBNull.Value);
        command.Parameters.AddWithValue("$details", (object?)details ?? DBNull.Value);
        return (long)(await command.ExecuteScalarAsync() ?? 0L);
    }

    public async Task UpdateAsync(long id, OperationStatus status, string? outputPath = null, string? errorReason = null, string? details = null)
    {
        await using var connection = new SqliteConnection(ConnectionString);
        await connection.OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE history
            SET status = $status, output_path = COALESCE($output_path, output_path),
                error_reason = $error_reason, details = COALESCE($details, details)
            WHERE id = $id;
            """;
        command.Parameters.AddWithValue("$id", id);
        command.Parameters.AddWithValue("$status", (int)status);
        command.Parameters.AddWithValue("$output_path", (object?)outputPath ?? DBNull.Value);
        command.Parameters.AddWithValue("$error_reason", (object?)errorReason ?? DBNull.Value);
        command.Parameters.AddWithValue("$details", (object?)details ?? DBNull.Value);
        await command.ExecuteNonQueryAsync();
    }

    public async Task<IReadOnlyList<HistoryRecord>> GetAllAsync()
    {
        var records = new List<HistoryRecord>();
        await using var connection = new SqliteConnection(ConnectionString);
        await connection.OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = "SELECT id, created_at, operation_type, source, output_path, status, error_reason, details FROM history ORDER BY created_at DESC;";
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            records.Add(new HistoryRecord(
                reader.GetInt64(0),
                DateTimeOffset.Parse(reader.GetString(1)),
                reader.GetString(2),
                reader.GetString(3),
                reader.IsDBNull(4) ? null : reader.GetString(4),
                (OperationStatus)reader.GetInt32(5),
                reader.IsDBNull(6) ? null : reader.GetString(6),
                reader.IsDBNull(7) ? null : reader.GetString(7)));
        }
        return records;
    }

    public async Task DeleteAsync(long id)
    {
        await ExecuteAsync("DELETE FROM history WHERE id = $id;", ("$id", id));
    }

    public async Task ClearAsync()
    {
        await ExecuteAsync("DELETE FROM history;");
        await logger.WriteAsync("HISTORY", "用户清空全部历史记录");
    }

    private async Task ExecuteAsync(string sql, params (string Name, object Value)[] parameters)
    {
        await using var connection = new SqliteConnection(ConnectionString);
        await connection.OpenAsync();
        var command = connection.CreateCommand();
        command.CommandText = sql;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value);
        await command.ExecuteNonQueryAsync();
    }
}
