CREATE TABLE currency_rates (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL,
    base_currency VARCHAR(3) NOT NULL,
    target_currency VARCHAR(3) NOT NULL,
    rate DECIMAL(19, 6) NOT NULL, -- Precision 19, Scale 6 should be ample for currency rates
    api_last_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_provider
        FOREIGN KEY(provider_id)
        REFERENCES rates_providers(id)
        ON DELETE CASCADE -- If a provider is deleted, its rates are also deleted
);

-- Optional: Add an index for faster lookups, e.g., by provider and target currency
CREATE INDEX IF NOT EXISTS idx_currency_rates_provider_target ON currency_rates (provider_id, target_currency, api_last_updated_at DESC);
