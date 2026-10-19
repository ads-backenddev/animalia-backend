package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado de POST /api/fotos: la URL que el cliente pone después en el
 * fotoUrl de su perfil, su alerta, su avistamiento o su animal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FotoResponse {

    private String url;
}
