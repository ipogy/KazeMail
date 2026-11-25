use thiserror::Error;

#[derive(Debug, Error, uniffi::Error)]
pub enum KazeCoreError {
    #[error("Connection failed: {detail}")]
    ConnectionFailed { detail: String },

    #[error("TLS handshake or certificate verification failed: {detail}")]
    TlsError { detail: String },

    #[error("Authentication failed: {detail}")]
    AuthFailed { detail: String },

    #[error("IMAP protocol error: {detail}")]
    ProtocolError { detail: String },

    #[error("I/O error: {detail}")]
    IoError { detail: String },

    #[error("Message parse or compression error: {detail}")]
    ParseError { detail: String },

    #[error("Blocked by non-destructive guardrail: {operation}")]
    NonDestructiveViolation { operation: String },

    #[error("Invalid input: {detail}")]
    InvalidInput { detail: String },
}

impl From<std::io::Error> for KazeCoreError {
    fn from(err: std::io::Error) -> Self {
        Self::IoError {
            detail: err.to_string(),
        }
    }
}

impl From<imap::Error> for KazeCoreError {
    fn from(err: imap::Error) -> Self {
        match err {
            imap::Error::Io(io_err) => Self::IoError {
                detail: io_err.to_string(),
            },
            imap::Error::ConnectionLost => Self::ConnectionFailed {
                detail: "IMAP connection lost unexpectedly".to_string(),
            },
            imap::Error::No(no) => Self::ProtocolError {
                detail: format!("IMAP NO: {no}"),
            },
            imap::Error::Bad(bad) => Self::ProtocolError {
                detail: format!("IMAP BAD: {bad}"),
            },
            imap::Error::Bye(bye) => Self::ConnectionFailed {
                detail: format!("IMAP BYE: {bye}"),
            },
            imap::Error::Parse(parse_err) => Self::ProtocolError {
                detail: format!("IMAP parse error: {parse_err}"),
            },
            other => Self::ProtocolError {
                detail: other.to_string(),
            },
        }
    }
}
