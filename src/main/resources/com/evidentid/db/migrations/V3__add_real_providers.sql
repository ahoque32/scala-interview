-- Add real currency providers to be used with the external ExchangeRate-API
INSERT INTO rates_providers (provider_name, currency_code, url) VALUES
    ('exchangerate-api', 'EUR', 'https://v6.exchangerate-api.com/v6/'),
    ('exchangerate-api', 'USD', 'https://v6.exchangerate-api.com/v6/'),
    ('exchangerate-api', 'JPY', 'https://v6.exchangerate-api.com/v6/'),
    ('exchangerate-api', 'MAD', 'https://v6.exchangerate-api.com/v6/');
