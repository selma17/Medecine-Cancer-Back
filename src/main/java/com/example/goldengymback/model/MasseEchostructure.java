package com.example.goldengymback.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class MasseEchostructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String localisation;
    private String distanceCentre;
    private String sein;
    private String mesure;
    private String forme;
    private String contours;
    private String orientation;
    private String comportementDesFaisceauxUltrasons;
    private String calcifications;
    private String densite;
    private String rayonHoraire;

    @ManyToOne
    @JoinColumn(name = "mammary_scan_id")
    @JsonBackReference
    private MammaryScan mammaryScan;
}