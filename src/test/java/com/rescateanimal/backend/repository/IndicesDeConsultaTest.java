package com.rescateanimal.backend.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los índices de V18: que existen con sus columnas y que sirven a las consultas
 * que los motivaron.
 *
 * Con las pocas filas de la base de datos de desarrollo, PostgreSQL prefiere
 * siempre un recorrido secuencial, así que el plan se pide con enable_seqscan
 * desactivado, solo dentro de la transacción de cada prueba: lo que se
 * comprueba es que el índice SIRVE para esa consulta, no que hoy convenga
 * usarlo. Las consultas son las de UsuarioRepository y NotificacionRepository
 * escritas en SQL, con valores fijos.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class IndicesDeConsultaTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transacciones;

    @Test
    @DisplayName("MATCH_REGISTRO se busca por publicación y tipo con índice: los destinatarios de ALERTA_ENCONTRADA")
    void indiceDeMatchPorPublicacion() {
        assertThat(definicion("idx_match_registro_publicacion"))
                .contains("(publicacion_id, tipo_publicacion)");

        assertThat(plan("""
                SELECT m.usuario_id FROM match_registro m
                WHERE m.tipo_publicacion = 'AVISTAMIENTO' AND m.publicacion_id = 1 AND m.interesado
                """)).contains("idx_match_registro_publicacion");
    }

    @Test
    @DisplayName("El listado de notificaciones sale del índice ya en su orden, sin ordenar en memoria")
    void indiceDeNotificacionesPorFecha() {
        assertThat(definicion("idx_notificacion_usuario_fecha"))
                .contains("(usuario_id, created_at DESC, id DESC)");

        assertThat(plan("""
                SELECT * FROM notificacion WHERE usuario_id = 1
                ORDER BY created_at DESC, id DESC LIMIT 20
                """)).contains("idx_notificacion_usuario_fecha").doesNotContain("Sort");
    }

    private String definicion(String indice) {
        List<String> filas = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, indice);
        assertThat(filas).as("existe el índice %s", indice).hasSize(1);
        return filas.get(0);
    }

    /** El plan de la consulta, sin recorridos secuenciales mientras dura la transacción. */
    private String plan(String consulta) {
        return new TransactionTemplate(transacciones).execute(estado -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + consulta, String.class));
        });
    }
}
