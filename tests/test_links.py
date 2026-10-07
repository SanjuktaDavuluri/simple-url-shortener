from conftest import BASE_URL


def test_creating_a_link_returns_its_short_url(make_client):
    client = make_client(codes=["Ab3xK9q"])

    response = client.post("/links", json={"url": "https://example.com/very/long"})

    assert response.status_code == 201
    assert response.json() == {
        "short_code": "Ab3xK9q",
        "short_url": f"{BASE_URL}/Ab3xK9q",
        "long_url": "https://example.com/very/long",
    }


def test_following_a_short_url_redirects_to_its_long_url(make_client):
    client = make_client(codes=["Ab3xK9q"])
    client.post("/links", json={"url": "https://example.com/very/long"})

    response = client.get("/Ab3xK9q")

    assert response.status_code == 302
    assert response.headers["location"] == "https://example.com/very/long"
    assert response.headers["cache-control"] == "no-store"


def test_an_unknown_short_code_is_not_found(make_client):
    client = make_client()

    response = client.get("/Nope123")

    assert response.status_code == 404


def test_links_survive_a_restart(make_client):
    before_restart = make_client(codes=["Ab3xK9q"])
    before_restart.post("/links", json={"url": "https://example.com/very/long"})

    after_restart = make_client(codes=[])

    response = after_restart.get("/Ab3xK9q")
    assert response.status_code == 302
    assert response.headers["location"] == "https://example.com/very/long"


def test_shortening_the_same_long_url_twice_creates_two_links(make_client):
    client = make_client(codes=["Ab3xK9q", "Zz9yX8w"])

    first = client.post("/links", json={"url": "https://example.com/same"}).json()
    second = client.post("/links", json={"url": "https://example.com/same"}).json()

    assert first["short_code"] == "Ab3xK9q"
    assert second["short_code"] == "Zz9yX8w"
    assert client.get("/Ab3xK9q").headers["location"] == "https://example.com/same"
    assert client.get("/Zz9yX8w").headers["location"] == "https://example.com/same"


def test_short_codes_are_case_sensitive(make_client):
    client = make_client(codes=["Ab3xK9q", "ab3xk9q"])
    client.post("/links", json={"url": "https://example.com/upper"})
    client.post("/links", json={"url": "https://example.com/lower"})

    assert client.get("/Ab3xK9q").headers["location"] == "https://example.com/upper"
    assert client.get("/ab3xk9q").headers["location"] == "https://example.com/lower"
