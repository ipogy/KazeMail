use crate::error::KazeCoreError;

const ZSTD_LEVEL: i32 = 3;

pub fn compress_zstd(data: &[u8]) -> Result<Vec<u8>, KazeCoreError> {
    zstd::bulk::compress(data, ZSTD_LEVEL).map_err(|e| KazeCoreError::ParseError {
        detail: format!("Zstd compression failed: {e}"),
    })
}

pub fn decompress_zstd(compressed: &[u8], max_output_bytes: usize) -> Result<Vec<u8>, KazeCoreError> {
    if compressed.is_empty() {
        return Ok(Vec::new());
    }
    let mut decoder = zstd::stream::Decoder::new(compressed).map_err(|e| KazeCoreError::ParseError {
        detail: format!("Zstd decoder init failed: {e}"),
    })?;
    let mut out = Vec::new();
    let mut limited = std::io::Read::take(&mut decoder, (max_output_bytes as u64) + 1);
    std::io::Read::read_to_end(&mut limited, &mut out).map_err(|e| KazeCoreError::ParseError {
        detail: format!("Zstd decompression failed: {e}"),
    })?;
    if out.len() > max_output_bytes {
        out.truncate(max_output_bytes);
    }
    Ok(out)
}
