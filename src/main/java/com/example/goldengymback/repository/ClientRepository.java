package com.example.goldengymback.repository;

import com.example.goldengymback.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClientRepository extends JpaRepository<Client, Long> {

    @Query("SELECT c FROM Client c WHERE c.medecin.id = :medecinId")
    List<Client> findByMedecinId(@Param("medecinId") Long medecinId);
}