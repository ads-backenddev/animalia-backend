-- ---------------------------------------------------------------------------
-- V8 - TOKEN_REVOCADO: que el logout sobreviva a un reinicio (RF-16, RF-17)
--
-- Hasta ahora las revocaciones vivian en un ConcurrentHashMap dentro de
-- TokenRevocadoService. Funcionaba, pero se perdian al reiniciar la aplicacion,
-- y reiniciar no es un caso raro: es cada despliegue. Un token del que el
-- usuario habia hecho logout volvia a autenticar durante lo que le quedara de
-- sus 24 horas de validez. Es justo el escenario que el logout existe para
-- evitar: cerrar sesion en un equipo compartido y que la sesion se reabra sola.
--
-- El enlace de recuperacion de contrasena no sufria esto porque tiene un
-- respaldo persistente: USUARIO.password_changed_at invalida cualquier token
-- anterior al cambio, este o no en la lista. El token de acceso no tenia
-- equivalente, y esa asimetria es la que cierra esta tabla.
--
-- LA CLAVE PRIMARIA ES EL jti. Ya es un UUID unico por token y es lo unico que
-- se consulta. Un id autogenerado obligaria a mantener ademas un indice unico
-- sobre el jti para hacer lo mismo, con una columna de mas y una indireccion en
-- el camino critico de cada peticion autenticada.
--
-- NO SE GUARDA EL TOKEN ENTERO, solo su identificador: el jti no sirve para
-- autenticarse, asi que una fuga de esta tabla no permite suplantar a nadie.
--
-- expira_en es timestamptz como el resto de marcas de tiempo desde V2: es un
-- instante en la linea del tiempo, no una hora local.
-- ---------------------------------------------------------------------------

CREATE TABLE token_revocado (
    jti        character varying(36)    NOT NULL,
    expira_en  timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_token_revocado PRIMARY KEY (jti)
);

-- La purga borra por caducidad; sin este indice recorreria la tabla entera cada
-- vez. Ojo: validate NO comprueba indices, asi que el @Index de la entidad no
-- basta y tiene que crearse aqui.
CREATE INDEX idx_token_revocado_expira_en ON token_revocado (expira_en);
