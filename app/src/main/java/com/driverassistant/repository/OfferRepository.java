package com.driverassistant.repository;

import com.driverassistant.domain.TripOffer;

import java.util.Optional;

/** Contrato reservado para persistência local futura. Nenhum banco é instalado na POC. */
public interface OfferRepository {
    void save(TripOffer offer);
    Optional<TripOffer> findLatest();
}
