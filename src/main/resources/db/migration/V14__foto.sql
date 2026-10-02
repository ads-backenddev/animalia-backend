-- ---------------------------------------------------------------------------
-- V14 - FOTO: quien subio cada archivo de POST /api/fotos
--
-- Hasta ahora la API no recibia archivos: fotoUrl era una cadena que el cliente
-- alojaba donde quisiera. Desde esta version las fotos las guarda el propio
-- backend en disco (el volumen de Railway en produccion), y esta tabla es el
-- registro de cada archivo. Sin ella no se podria:
--   - exigir que un fotoUrl apunte a una foto subida por quien la usa, y no a
--     la de otra persona copiada de una respuesta publica;
--   - borrar la foto de perfil al anonimizar una cuenta (RF-19) sabiendo de
--     quien es;
--   - limpiar los archivos que se subieron y nunca se llegaron a usar.
--
-- LA CLAVE PRIMARIA ES EL NOMBRE DEL ARCHIVO ("<uuid>.jpg"), no un id
-- autogenerado: ya es unico, es lo que lleva la URL y es lo unico por lo que se
-- busca. Mismo criterio que el jti de TOKEN_REVOCADO (V8). 40 caracteres: 36 del
-- UUID y 4 de la extension.
--
-- NO HAY COLUMNA DE TIPO: el formato lo dice la extension del nombre, que se
-- elige a partir de los bytes reales del archivo, no de lo que declare el
-- cliente. Una columna aparte solo podria contradecirla.
--
-- QUE FILAS LA USAN NO SE GUARDA AQUI. Una foto esta "en uso" si algun foto_url
-- de USUARIO, ALERTA, AVISTAMIENTO o ANIMAL_ADOPCION apunta a ella, y eso se
-- consulta en el momento. Guardarlo tambien aqui seria una segunda copia del
-- mismo hecho que habria que mantener sincronizada en cinco services.
--
-- La clave ajena a USUARIO no lleva ON DELETE: las cuentas no se borran, se
-- anonimizan (RF-19), y la fila de la foto de perfil la quita el propio service.
-- ---------------------------------------------------------------------------

CREATE TABLE foto (
    nombre      character varying(40)    NOT NULL,
    usuario_id  bigint                   NOT NULL,
    tamanyo     integer                  NOT NULL,
    created_at  timestamp with time zone NOT NULL,
    CONSTRAINT pk_foto PRIMARY KEY (nombre),
    CONSTRAINT fk_foto_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_foto_tamanyo CHECK (tamanyo > 0)
);

-- La limpieza diaria busca las fotos con mas de 24 h sin usar: filtra por fecha
-- antes de comprobar si alguien las referencia. Sin este indice recorreria la
-- tabla entera cada dia. No se crea uno sobre usuario_id porque ninguna consulta
-- busca por el: la comprobacion de propiedad va por la clave primaria.
CREATE INDEX idx_foto_created_at ON foto (created_at);
