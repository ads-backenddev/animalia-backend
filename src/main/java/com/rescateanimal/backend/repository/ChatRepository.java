package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Chat. Sin lógica de negocio.
 */
@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {
}
