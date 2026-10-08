ALTER TABLE idee_price ADD CONSTRAINT idee_price_free_amount CHECK (price_type <> 'free' OR (amount IS NOT NULL AND amount = 0));
-- Catégories utilisables aussi sur une base neuve sans contexte demo.
INSERT INTO idee_category (slug,name,icon) VALUES
 ('nature','Nature & balades','trees'),('culture','Culture & patrimoine','building-bank'),
 ('marche','Marchés & terroir','basket'),('famille','En famille','users'),
 ('spectacle','Concerts & spectacles','music'),('atelier','Ateliers & rencontres','palette')
 ON CONFLICT (slug) DO NOTHING;
