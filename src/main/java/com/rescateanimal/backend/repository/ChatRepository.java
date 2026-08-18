package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Capa de datos de Chat. Sin lógica de negocio.
 */
@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {

    /**
     * Chats de un conjunto de matches, en una sola consulta.
     *
     * Existe en plural para que el listado de /api/matches/mios no dispare una
     * consulta por match al rellenar el chatId (N+1).
     */
    List<Chat> findByMatchIdIn(Collection<Long> matchIds);
}
