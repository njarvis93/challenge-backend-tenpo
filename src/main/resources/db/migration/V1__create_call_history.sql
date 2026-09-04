-- Historial de llamadas a la API. Tabla unica y plana: se escribe mucho
-- (una fila por request) y se lee paginado por fecha descendente.
CREATE TABLE call_history (
    id            BIGSERIAL     PRIMARY KEY,
    called_at     TIMESTAMPTZ   NOT NULL,
    endpoint      VARCHAR(512)  NOT NULL,
    http_method   VARCHAR(10)   NOT NULL,
    parameters    TEXT,
    response_body TEXT,
    error_message TEXT,
    status_code   INTEGER,
    duration_ms   BIGINT
);

-- Soporta el orden por defecto del endpoint de historial (mas reciente primero).
CREATE INDEX idx_call_history_called_at ON call_history (called_at DESC);
