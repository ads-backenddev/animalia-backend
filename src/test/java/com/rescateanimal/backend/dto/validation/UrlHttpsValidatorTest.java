package com.rescateanimal.backend.dto.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlHttpsValidatorTest {

    private final UrlHttpsValidator validator = new UrlHttpsValidator();

    @ParameterizedTest
    @DisplayName("Acepta URLs https con host")
    @ValueSource(strings = {
            "https://cdn.test/luna.jpg",
            "https://firebasestorage.googleapis.com/v0/b/app.appspot.com/o/a%2Fb.jpg?alt=media&token=x",
            "HTTPS://Example.com/foto.png",
            "  https://cdn.test/recortada.jpg  "
    })
    void aceptaHttps(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("Null y en blanco los decide @NotBlank, no esta validación")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void nullYBlancoSonValidos(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("Rechaza lo que no es una URL https con host")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:image/png;base64,iVBORw0KGgo=",
            "http://cdn.test/luna.jpg",
            "ftp://cdn.test/luna.jpg",
            "//cdn.test/luna.jpg",
            "/uploads/luna.jpg",
            "luna.jpg",
            "hola",
            "https://",
            "https:///luna.jpg",
            "https://usuario@sitio-falso.com/luna.jpg",
            "https://cdn.test/con espacio.jpg"
    })
    void rechazaLoDemas(String url) {
        assertThat(validator.isValid(url, null)).isFalse();
    }
}
