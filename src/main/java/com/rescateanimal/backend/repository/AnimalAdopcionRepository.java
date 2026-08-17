package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.AnimalAdopcion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de AnimalAdopcion. Sin lógica de negocio.
 * Aquí vivirán los filtros del catálogo por especie, tamaño y zona (RF-22).
 */
@Repository
public interface AnimalAdopcionRepository extends JpaRepository<AnimalAdopcion, Long> {
}
