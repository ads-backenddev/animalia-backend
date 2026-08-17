---
name: nuevo-endpoint
description: Usar al implementar un nuevo endpoint REST del backend. Sigue la arquitectura por capas del proyecto (controller -> service -> repository) y las convenciones de DTOs.
---

# Implementar un nuevo endpoint

Al recibir la petición de implementar un endpoint, sigue siempre este orden:

1. Busca el endpoint en docs/api-rest.md para confirmar método, ruta, body de entrada y respuesta
   esperada.
2. Crea/actualiza el DTO de entrada en dto/request/ si no existe.
3. Crea/actualiza el DTO de salida en dto/response/ si no existe.
4. Implementa el método en el service/ correspondiente — TODA la lógica de negocio vive aquí, nunca
   en el controller.
5. Implementa el método del controller/, que solo valida entrada (@Valid), llama al service y
   devuelve el DTO de respuesta con el código HTTP correcto.
6. Si el endpoint requiere rol específico, añade @PreAuthorize con el rol correspondiente.
7. Indica qué pruebas unitarias deberían escribirse para este endpoint, pero no las implementes salvo
   que se te pida explícitamente.
