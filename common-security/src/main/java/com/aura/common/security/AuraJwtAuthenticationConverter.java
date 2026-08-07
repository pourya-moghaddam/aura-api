package com.aura.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns a validated JWT into an authenticated principal.
 *
 * <p>Grants two kinds of authority:
 * <ul>
 *   <li>{@code ROLE_ADMIN}, {@code ROLE_SELLER}, … from the {@code roles} claim — for
 *       {@code hasRole(...)}.</li>
 *   <li>{@code AUD_control} / {@code AUD_storefront} from the {@code aud} claim — for
 *       {@link ControlPanelOnly}.</li>
 * </ul>
 *
 * <p>The authority mapping is the part the previous hand-rolled filter got wrong: it parsed roles
 * into the token but handed Spring an empty authority list on the way back in, so every
 * {@code hasRole(...)} check silently failed.
 */
public class AuraJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        List<String> roles = jwt.getClaimAsStringList(AuraClaims.ROLES);
        if (roles != null) {
            roles.stream()
                .map(Roles::authority)
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        }

        for (String audience : jwt.getAudience()) {
            try {
                authorities.add(new SimpleGrantedAuthority(TokenAudience.fromValue(audience).authority()));
            } catch (IllegalArgumentException ignored) {
                // An audience we don't recognise grants nothing. Not an error: it just means this
                // token was minted for a surface this service does not serve.
            }
        }

        // Principal name is the user id, so authentication.getName() is directly usable.
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
