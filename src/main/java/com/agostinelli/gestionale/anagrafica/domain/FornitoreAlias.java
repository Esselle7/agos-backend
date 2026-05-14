package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "fornitore_alias_matching")
public class FornitoreAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Integer id;

    @Column(name = "fornitore_id", nullable = false, columnDefinition = "uuid")
    public UUID fornitoreId;

    @Column(name = "pattern", nullable = false, length = 255)
    public String pattern;

    // Valori attesi: CONTAINS, STARTS_WITH, REGEX (da lk_match_types)
    @Column(name = "match_type", nullable = false, length = 50)
    public String matchType;
}
