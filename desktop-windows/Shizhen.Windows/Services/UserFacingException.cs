namespace Shizhen.Windows.Services;

public sealed class UserFacingException(string message, string? technicalDetails = null, Exception? innerException = null)
    : Exception(message, innerException)
{
    public string? TechnicalDetails { get; } = technicalDetails;
}
