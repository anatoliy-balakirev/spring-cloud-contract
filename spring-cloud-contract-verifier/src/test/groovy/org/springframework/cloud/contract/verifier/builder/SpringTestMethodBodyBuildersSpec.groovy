/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.contract.verifier.builder

import java.util.regex.Pattern

import org.junit.Rule
import spock.lang.Issue
import spock.lang.Shared
import spock.lang.Specification
import spock.util.environment.RestoreSystemProperties

import org.springframework.boot.test.rule.OutputCapture
import org.springframework.cloud.contract.spec.Contract
import org.springframework.cloud.contract.verifier.config.ContractVerifierConfigProperties
import org.springframework.cloud.contract.verifier.config.TestFramework
import org.springframework.cloud.contract.verifier.config.TestMode
import org.springframework.cloud.contract.verifier.dsl.wiremock.WireMockStubVerifier
import org.springframework.cloud.contract.verifier.file.ContractMetadata
import org.springframework.cloud.contract.verifier.util.SyntaxChecker

/**
 * @author Jakub Kubrynski, codearte.io
 * @author Tim Ysewyn
 */
class SpringTestMethodBodyBuildersSpec extends Specification implements WireMockStubVerifier {

	@Rule
	OutputCapture capture = new OutputCapture()

	@Shared
	ContractVerifierConfigProperties properties = new ContractVerifierConfigProperties(
			assertJsonSize: true
	)

	@Shared
	SingleTestGenerator.GeneratedClassData generatedClassData =
			new SingleTestGenerator.GeneratedClassData("foo", "com.example", new File(".").toPath())

	@Shared
	Contract contractDslWithCookiesValue = Contract.make {
		request {
			method "GET"
			url "/foo"
			headers {
				header 'Accept': 'application/json'
			}
			cookies {
				cookie 'cookie-key': 'cookie-value'
			}
		}
		response {
			status 200
			headers {
				header 'Content-Type': 'application/json'
			}
			cookies {
				cookie 'cookie-key': 'new-cookie-value'
			}
			body([status: 'OK'])
		}
	}

	@Shared
	Contract contractDslWithCookiesPattern = Contract.make {
		request {
			method "GET"
			url "/foo"
			headers {
				header 'Accept': 'application/json'
			}
			cookies {
				cookie 'cookie-key': regex('[A-Za-z]+')
			}
		}
		response {
			status 200
			headers {
				header 'Content-Type': 'application/json'
			}
			cookies {
				cookie 'cookie-key': regex('[A-Za-z]+')
			}
			body([status: 'OK'])
		}
	}

	@Shared
	Contract contractDslWithAbsentCookies = Contract.make {
		request {
			method "GET"
			url "/foo"
			cookies {
				cookie 'cookie-key': absent()
			}
		}
		response {
			status 200
			body([status: 'OK'])
		}
	}

	@Shared
	// tag::contract_with_regex[]
	Contract dslWithOptionalsInString = Contract.make {
		priority 1
		request {
			method POST()
			url '/users/password'
			headers {
				contentType(applicationJson())
			}
			body(
					email: $(consumer(optional(regex(email()))), producer('abc@abc.com')),
					callback_url: $(consumer(regex(hostname())), producer('http://partners.com'))
			)
		}
		response {
			status 404
			headers {
				contentType(applicationJson())
			}
			body(
					code: value(consumer("123123"), producer(optional("123123"))),
					message: "User not found by email = [${value(producer(regex(email())), consumer('not.existing@user.com'))}]"
			)
		}
	}
	// end::contract_with_regex[]

	@Shared
	Contract dslWithOptionals = Contract.make {
		priority 1
		request {
			method POST()
			url '/users/password'
			headers {
				contentType(applicationJson())
			}
			body(
					""" {
								"email" : "${
						value(consumer(optional(regex(email()))), producer('abc@abc.com'))
					}",
								"callback_url" : "${
						value(consumer(regex(hostname())), producer('http://partners.com'))
					}"
								}
							"""
			)
		}
		response {
			status 404
			headers {
				contentType(applicationJson())
			}
			body(
					""" {
								"code" : "${value(consumer(123123), producer(optional(123123)))}",
								"message" : "User not found by email = [${
						value(producer(regex(email())), consumer('not.existing@user.com'))
					}]"
								}
							"""
			)
		}
	}

	def setup() {
		properties = new ContractVerifierConfigProperties(
				assertJsonSize: true
		)
	}

	def 'should generate assertions for simple response body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method GET()
					url "test"
				}
				response {
					status OK()
					body """{
	"property1": "a",
	"property2": "b"
}"""
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property1']").isEqualTo("a")""")
			test.contains("""assertThatJson(parsedJson).field("['property2']").isEqualTo("b")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	private String singleTestGenerator(Contract contractDsl) {
		return new JavaTestGenerator() {
			@Override
			ClassBodyBuilder classBodyBuilder(BlockBuilder builder, GeneratedClassMetaData metaData, SingleMethodBuilder methodBuilder) {
				return super.classBodyBuilder(builder, metaData, methodBuilder).field(new Field() {
					@Override
					boolean accept() {
						return metaData.configProperties.testMode == TestMode.JAXRSCLIENT
					}

					@Override
					Field call() {
						builder.addLine("WebTarget webTarget")
						return this
					}
				})
			}
		}.buildClass(properties, [contractMetadata(contractDsl)], "foo", generatedClassData)
	}

	private GeneratedClassMetaData generatedClassMetaData(Contract contractDsl) {
		new GeneratedClassMetaData(properties, [contractMetadata(contractDsl)], "foo", generatedClassData)
	}

	ContractMetadata contractMetadata(Contract contractDsl) {
		return new ContractMetadata(new File(".").toPath(), false, 0, null, contractDsl)
	}

	@Issue('#187')
	def 'should generate assertions for null and boolean values with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method GET()
					url 'test'
				}
				response {
					status OK()
					body """{
	"property1": "true",
	"property2": null,
	"property3": false
}"""
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property1']").isEqualTo("true")""")
			test.contains("""assertThatJson(parsedJson).field("['property2']").isNull()""")
			test.contains("""assertThatJson(parsedJson).field("['property3']").isEqualTo(false)""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#79')
	def 'should generate assertions for simple response body constructed from map with a list with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "GET"
					url "test"
				}
				response {
					status OK()
					body(
							property1: 'a',
							property2: [
									[a: 'sth'],
									[b: 'sthElse']
							]
					)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property1']").isEqualTo("a")""")
			test.contains("""assertThatJson(parsedJson).array("['property2']").contains("['a']").isEqualTo("sth")""")
			test.contains("""assertThatJson(parsedJson).array("['property2']").contains("['b']").isEqualTo("sthElse")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#79')
	@RestoreSystemProperties
	def 'should generate assertions for simple response body constructed from map with a list with #methodBuilderName with array size check'() {
		given:
			System.setProperty('spring.cloud.contract.verifier.assert.size', 'true')
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url 'test'
				}
				response {
					status OK()
					body(
							property1: 'a',
							property2: [
									[a: 'sth'],
									[b: 'sthElse']
							]
					)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property1']").isEqualTo("a")""")
			test.contains("""assertThatJson(parsedJson).array("['property2']").contains("['a']").isEqualTo("sth")""")
			test.contains("""assertThatJson(parsedJson).array("['property2']").hasSize(2)""")
			test.contains("""assertThatJson(parsedJson).array("['property2']").contains("['b']").isEqualTo("sthElse")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#82')
	def 'should generate proper request when body constructed from map with a list #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "GET"
					url "test"
					body(
							items: ['HOP']
					)
				}
				response {
					status OK()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(bodyString)
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | bodyString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | """.body('''{\"items\":[\"HOP\"]}''')"""
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                                                      | '.body("{\\"items\\":[\\"HOP\\"]}")'
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                                                      | '.body("{\\"items\\":[\\"HOP\\"]}")'
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                                                      | '.body("{\\"items\\":[\\"HOP\\"]}")'
	}

	@Issue('#88')
	def 'should generate proper request when body constructed from GString with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url 'test'
					body(
							'property1=VAL1'
					)
				}
				response {
					status OK()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(bodyString)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		where:
			methodBuilderName | methodBuilder                                      | bodyString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | """.body('''property1=VAL1''')"""
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '.body("property1=VAL1")'
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '.body("property1=VAL1")'
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '.body("property1=VAL1")'
	}

	@Issue('185')
	def 'should generate assertions for a response body containing map with integers as keys with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url 'test'
				}
				response {
					status OK()
					body(
							property: [
									14: 0.0,
									7 : 0.0
							]
					)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property']").field(7).isEqualTo(0.0)""")
			test.contains("""assertThatJson(parsedJson).field("['property']").field(14).isEqualTo(0.0)""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate assertions for array in response body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url 'test'
				}
				response {
					status OK()
					body """[
{
	"property1": "a"
},
{
	"property2": "b"
}]"""
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).array().contains("['property2']").isEqualTo("b")""")
			test.contains("""assertThatJson(parsedJson).array().contains("['property1']").isEqualTo("a")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate assertions for array inside response body element with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "GET"
					url "test"
				}
				response {
					status OK()
					body """{
	"property1": [
	{ "property2": "test1"},
	{ "property3": "test2"}
	]
}"""
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).array("['property1']").contains("['property2']").isEqualTo("test1")""")
			test.contains("""assertThatJson(parsedJson).array("['property1']").contains("['property3']").isEqualTo("test2")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate assertions for nested objects in response body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "GET"
					url "test"
				}
				response {
					status OK()
					body '''\
{
	"property1": "a",
	"property2": {"property3": "b"}
}
'''
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property2']").field("['property3']").isEqualTo("b")""")
			test.contains("""assertThatJson(parsedJson).field("['property1']").isEqualTo("a")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate regex assertions for map objects in response body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "GET"
					url "test"
				}
				response {
					status OK()
					body(
							property1: "a",
							property2: value(
									consumer('123'),
									producer(regex('[0-9]{3}'))
							)
					)
					headers {
						contentType(applicationJson())
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property2']").matches("[0-9]{3}")""")
			test.contains("""assertThatJson(parsedJson).field("['property1']").isEqualTo("a")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate regex assertions for string objects in response body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url 'test'
				}
				response {
					status OK()
					body("""{"property1":"a","property2":"${
						value(consumer('123'), producer(regex('[0-9]{3}')))
					}"}""")
					headers {
						contentType(applicationJson())
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property2']").matches("[0-9]{3}")""")
			test.contains("""assertThatJson(parsedJson).field("['property1']").isEqualTo("a")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue(['#126', '#143'])
	def 'should generate escaped regex assertions for string objects in response body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url 'test'
				}
				response {
					status OK()
					body("""{"property":"  ${
						value(consumer('123'), producer(regex('\\d+')))
					}"}""")
					headers {
						contentType(applicationJson())
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['property']").matches("\\\\d+")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate a call with an url path and query parameters with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath("/users/${value(regex("1"))}") {
						queryParameters {
							parameter 'limit': $(consumer(equalTo('20')), producer(equalTo('10')))
							parameter 'offset': $(consumer(containing("20")), producer(equalTo('20')))
							parameter 'filter': 'email'
							parameter 'sort': equalTo("name")
							parameter 'search': $(consumer(notMatching(~/^\/[0-9]{2}$/)), producer('55'))
							parameter 'age': $(consumer(notMatching("^\\w*\$")), producer('99'))
							parameter 'name': $(consumer(matching('Denis.*')), producer('Denis.Stepanov'))
							parameter 'email': 'bob@email.com'
							parameter 'hello': $(consumer(matching('Denis.*')), producer(absent()))
							parameter 'hello': absent()
						}
					}
				}
				response {
					status OK()
					body """
					{
						"property1": "a",
						"property2": "b"
					}
					"""
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''.queryParam("limit","10")''')
			test.contains('''.queryParam("offset","20")''')
			test.contains('''.queryParam("filter","email")''')
			test.contains('''.queryParam("sort","name")''')
			test.contains('''.queryParam("search","55")''')
			test.contains('''.queryParam("age","99")''')
			test.contains('''.queryParam("name","Denis.Stepanov")''')
			test.contains('''.queryParam("email","bob@email.com")''')
			test.contains('''.get("/users/1")''')
			test.contains('assertThatJson(parsedJson).field("[\'property1\']").isEqualTo("a")')
			test.contains('assertThatJson(parsedJson).field("[\'property2\']").isEqualTo("b")')
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#169')
	def 'should generate a call with an url path and query parameters with url containing a pattern with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url($(consumer(regex('/foo/[0-9]+')), producer('/foo/123456'))) {
						queryParameters {
							parameter 'limit': $(consumer(equalTo('20')), producer(equalTo('10')))
							parameter 'offset': $(consumer(containing('20')), producer(equalTo('20')))
							parameter 'filter': 'email'
							parameter 'sort': equalTo('name')
							parameter 'search': $(consumer(notMatching(~/^\/[0-9]{2}$/)), producer('55'))
							parameter 'age': $(consumer(notMatching("^\\w*\$")), producer('99'))
							parameter 'name': $(consumer(matching('Denis.*')), producer('Denis.Stepanov'))
							parameter 'email': 'bob@email.com'
							parameter 'hello': $(consumer(matching('Denis.*')), producer(absent()))
							parameter 'hello': absent()
						}
					}
				}
				response {
					status OK()
					body """
					{
						"property1": "a",
						"property2": "b"
					}
					"""
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''.queryParam("limit","10")''')
			test.contains('''.queryParam("offset","20")''')
			test.contains('''.queryParam("filter","email")''')
			test.contains('''.queryParam("sort","name")''')
			test.contains('''.queryParam("search","55")''')
			test.contains('''.queryParam("age","99")''')
			test.contains('''.queryParam("name","Denis.Stepanov")''')
			test.contains('''.queryParam("email","bob@email.com")''')
			test.contains('''.get("/foo/123456")''')
			test.contains('assertThatJson(parsedJson).field("[\'property1\']").isEqualTo("a")')
			test.contains('assertThatJson(parsedJson).field("[\'property2\']").isEqualTo("b")')
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate test for empty body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method(POST())
					url('/ws/payments')
					body("")
				}
				response {
					status 406
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(bodyString)
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | bodyString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | ".body('''''')"
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '.body("")'
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '.body("")'
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '.body("")'
	}

	def 'should generate test for String in response body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'POST'
					url 'test'
				}
				response {
					status OK()
					body 'test'
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(bodyDefinitionString)
			test.contains(bodyEvaluationString)
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder | bodyDefinitionString                                   | bodyEvaluationString
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | 'String responseBody = response.body.asString()'       | "responseBody == 'test'"
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | 'String responseBody = response.getBody().asString();' | 'assertThat(responseBody).isEqualTo("test");'
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | 'String responseBody = response.getBody().asString();' | 'assertThat(responseBody).isEqualTo("test");'
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | 'String responseBody = response.getBody().asString();' | 'assertThat(responseBody).isEqualTo("test");'
	}

	@Issue('113')
	def 'should generate regex test for String in response header with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'POST'
					url $(consumer(regex('/partners/[0-9]+/users')), producer('/partners/1000/users'))
					headers { contentType(applicationJson()) }
					body(
							first_name: 'John',
							last_name: 'Smith',
							personal_id: '12345678901',
							phone_number: '500500500',
							invitation_token: '00fec7141bb94793bfe7ae1d0f39bda0',
							password: 'john'
					)
				}
				response {
					status 201
					headers {
						header 'Location': $(consumer('http://localhost/partners/1000/users/1001'), producer(regex('http://localhost/partners/[0-9]+/users/[0-9]+')))
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(headerEvaluationString)
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | headerEvaluationString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | '''response.header("Location") ==~ java.util.regex.Pattern.compile('http://localhost/partners/[0-9]+/users/[0-9]+')'''
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                                                      | 'assertThat(response.header("Location")).matches("http://localhost/partners/[0-9]+/users/[0-9]+");'
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                                                      | 'assertThat(response.header("Location")).matches("http://localhost/partners/[0-9]+/users/[0-9]+");'
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                                                      | 'assertThat(response.header("Location")).matches("http://localhost/partners/[0-9]+/users/[0-9]+");'
	}

	@Issue('115')
	def 'should generate regex with helper method with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'POST'
					url $(consumer(regex('/partners/[0-9]+/users')), producer('/partners/1000/users'))
					headers { contentType(applicationJson()) }
					body(
							first_name: 'John',
							last_name: 'Smith',
							personal_id: '12345678901',
							phone_number: '500500500',
							invitation_token: '00fec7141bb94793bfe7ae1d0f39bda0',
							password: 'john'
					)
				}
				response {
					status 201
					headers {
						header 'Location': $(consumer('http://localhost/partners/1000/users/1001'), producer(regex("^${hostname()}/partners/[0-9]+/users/[0-9]+")))
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(headerEvaluationString)
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | headerEvaluationString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | '''response.header("Location") ==~ java.util.regex.Pattern.compile('^((http[s]?|ftp):/)/?([^:/\\\\s]+)(:[0-9]{1,5})?/partners/[0-9]+/users/[0-9]+')'''
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                                                      | 'assertThat(response.header("Location")).matches("^((http[s]?|ftp):/)/?([^:/\\\\s]+)(:[0-9]{1,5})?/partners/[0-9]+/users/[0-9]+");'
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                                                      | 'assertThat(response.header("Location")).matches("^((http[s]?|ftp):/)/?([^:/\\\\s]+)(:[0-9]{1,5})?/partners/[0-9]+/users/[0-9]+");'
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                                                      | 'assertThat(response.header("Location")).matches("^((http[s]?|ftp):/)/?([^:/\\\\s]+)(:[0-9]{1,5})?/partners/[0-9]+/users/[0-9]+");'
	}

	def 'should work with more complex stuff and jsonpaths with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				priority 10
				request {
					method 'POST'
					url '/validation/client'
					headers {
						contentType(applicationJson())
					}
					body(
							bank_account_number: '0014282912345698765432161182',
							email: 'foo@bar.com',
							phone_number: '100299300',
							personal_id: 'ABC123456'
					)
				}

				response {
					status OK()
					body(errors: [
							[property: "bank_account_number", message: "incorrect_format"]
					])
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).array("['errors']").contains("['property']").isEqualTo("bank_account_number")""")
			test.contains("""assertThatJson(parsedJson).array("['errors']").contains("['message']").isEqualTo("incorrect_format")""")
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should work properly with GString url with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {

				request {
					method PUT()
					url "/partners/${value(consumer(regex('^[0-9]*$')), producer('11'))}/agents/11/customers/09665703Z"
					headers {
						contentType(applicationJson())
					}
					body(
							first_name: 'Josef',
					)
				}
				response {
					status 422
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''/partners/11/agents/11/customers/09665703Z''')
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should resolve properties in GString with regular expression with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				priority 1
				request {
					method POST()
					url '/users/password'
					headers {
						contentType(applicationJson())
					}
					body(
							email: $(consumer(regex(email())), producer('not.existing@user.com')),
							callback_url: $(consumer(regex(hostname())), producer('http://partners.com'))
					)
				}
				response {
					status 404
					headers {
						contentType(applicationJson())
					}
					body(
							code: 4,
							message: "User not found by email = [${value(producer(regex(email())), consumer('not.existing@user.com'))}]"
					)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("""assertThatJson(parsedJson).field("['message']").matches("User not found by email = \\\\\\\\[[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\\\\\.[a-zA-Z]{2,6}\\\\\\\\]")""")
		and:
			// no static compilation due to bug in Groovy https://issues.apache.org/jira/browse/GROOVY-8055
			SyntaxChecker.tryToCompileWithoutCompileStatic(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('42')
	def 'should not omit the optional field in the test creation with MockMvcSpockMethodBodyBuilder'() {
		given:
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''"email":"abc@abc.com"''')
			test.contains("""assertThatJson(parsedJson).field("['code']").matches("(123123)?")""")
			!test.contains('''REGEXP''')
			!test.contains('''OPTIONAL''')
			!test.contains('''OptionalProperty''')
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
		where:
			contractDsl << [dslWithOptionals, dslWithOptionalsInString]
	}

	@Issue('42')
	def "should not omit the optional field in the test creation with MockMvcJUnitMethodBodyBuilder"() {
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('\\"email\\":\\"abc@abc.com\\"')
			test.contains('assertThatJson(parsedJson).field("[\'code\']").matches("(123123)?");')
			!test.contains('''REGEXP''')
			!test.contains('''OPTIONAL''')
			!test.contains('''OptionalProperty''')
		and:
			SyntaxChecker.tryToCompileJava("mockmvc", test)
		where:
			contractDsl << [dslWithOptionals, dslWithOptionalsInString]
	}

	@Issue('72')
	def 'should make the execute method work with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method """PUT"""
					url """/fraudcheck"""
					body("""
                        {
                        "clientPesel":"${
						value(consumer(regex('[0-9]{10}')), producer('1234567890'))
					}",
                        "loanAmount":123.123
                        }
                    """
					)
					headers {
						header("""Content-Type""", """application/vnd.fraud.v1+json""")
					}

				}
				response {
					status OK()
					body("""{
    "fraudCheckStatus": "OK",
    "rejectionReason": ${
						value(consumer(null), producer(execute('assertThatRejectionReasonIsNull($it)')))
					}
}""")
					headers {
						header('Content-Type': 'application/vnd.fraud.v1+json')
						header 'Location': value(
								consumer(null),
								producer(execute('assertThatLocationIsNull($it)'))
						)
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			assertionStrings.each { String assertionString ->
				assert test.contains(assertionString)
			}
		where:
			methodBuilderName | methodBuilder | assertionStrings
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | ['''assertThatRejectionReasonIsNull(parsedJson.read("\\$.rejectionReason"))''', '''assertThatLocationIsNull(response.header("Location"))''']
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | ['''assertThatRejectionReasonIsNull(parsedJson.read("$.rejectionReason"))''', '''assertThatLocationIsNull(response.header("Location"))''']
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | ['''assertThatRejectionReasonIsNull(parsedJson.read("$.rejectionReason"))''', '''assertThatLocationIsNull(response.header("Location"))''']
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | ['''assertThatRejectionReasonIsNull(parsedJson.read("$.rejectionReason"))''', '''assertThatLocationIsNull(response.header("Location"))''']
	}

	def 'should support inner map and list definitions with #methodBuilderName'() {
		given:

			Pattern PHONE_NUMBER = Pattern.compile(/[+\w]*/)
			Pattern ANYSTRING = Pattern.compile(/.*/)
			Pattern NUMBERS = Pattern.compile(/[\d\.]*/)
			Pattern DATETIME = ANYSTRING

			Contract contractDsl = Contract.make {
				request {
					method "PUT"
					url "/v1/payments/e86df6f693de4b35ae648464c5b0dc09/client_data"
					headers {
						contentType(applicationJson())
					}
					body(
							client: [
									first_name   : $(consumer(regex(onlyAlphaUnicode())), producer('Denis')),
									last_name    : $(consumer(regex(onlyAlphaUnicode())), producer('FakeName')),
									email        : $(consumer(regex(email())), producer('fakemail@fakegmail.com')),
									fax          : $(consumer(PHONE_NUMBER), producer('+xx001213214')),
									phone        : $(consumer(PHONE_NUMBER), producer('2223311')),
									data_of_birth: $(consumer(DATETIME), producer('2002-10-22T00:00:00Z'))
							],
							client_id_card: [
									id           : $(consumer(ANYSTRING), producer('ABC12345')),
									date_of_issue: $(consumer(ANYSTRING), producer('2002-10-02T00:00:00Z')),
									address      : [
											street : $(consumer(ANYSTRING), producer('Light Street')),
											city   : $(consumer(ANYSTRING), producer('Fire')),
											region : $(consumer(ANYSTRING), producer('Skys')),
											country: $(consumer(ANYSTRING), producer('HG')),
											zip    : $(consumer(NUMBERS), producer('658965'))
									]
							],
							incomes_and_expenses: [
									monthly_income         : $(consumer(NUMBERS), producer('0.0')),
									monthly_loan_repayments: $(consumer(NUMBERS), producer('100')),
									monthly_living_expenses: $(consumer(NUMBERS), producer('22'))
							],
							additional_info: [
									allow_to_contact: $(consumer(optional(regex(anyBoolean()))), producer('true'))
							]
					)
				}
				response {
					status OK()
					headers {
						contentType(applicationJson())
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains bodyString
			!test.contains("clientValue")
			!test.contains("cursor")
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | bodyString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | '"street":"Light Street"'
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '\\"street\\":\\"Light Street\\"'
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '\\"street\\":\\"Light Street\\"'
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '\\"street\\":\\"Light Street\\"'

	}

	def 'should work with optional fields that have null #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "PUT"
					url "/v1/payments/e86df6f693de4b35ae648464c5b0dc09/client_data"
					headers {
						contentType(applicationJson())
					}
				}
				response {
					status OK()
					headers {
						contentType(applicationJson())
					}
					body(
							code: $(optional(regex('123123')))
					)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | bodyString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | '"street":"Light Street"'
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '\\"street\\":\\"Light Street\\"'
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '\\"street\\":\\"Light Street\\"'
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '\\"street\\":\\"Light Street\\"'

	}

	def "shouldn't generate unicode escape characters with #methodBuilderName"() {
		given:
			Pattern ONLY_ALPHA_UNICODE = Pattern.compile(/[\p{L}]*/)

			Contract contractDsl = Contract.make {
				request {
					method "PUT"
					url '/v1/payments/e86df6f693de4b35ae648464c5b0dc09/енев'
					headers {
						contentType(applicationJson())
					}
					body(
							client: [
									first_name: $(consumer(ONLY_ALPHA_UNICODE), producer('Пенева')),
									last_name : $(consumer(ONLY_ALPHA_UNICODE), producer('Пенева'))
							]
					)
				}
				response {
					status OK()
					headers {
						contentType(applicationJson())
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			!test.contains("\\u041f")
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('177')
	def 'should generate proper test code when having multiline body with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'PUT'
					url '/multiline'
					body('''hello,
World.''')
				}
				response {
					status OK()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(bodyString)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | bodyString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | """'''hello,
World.'''"""
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '"hello,\\nWorld."'
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '"hello,\\nWorld."'
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '"hello,\\nWorld."'
	}

	@Issue('180')
	def 'should generate proper test code when having multipart parameters with #methodBuilderName'() {
		given:
			// tag::multipartdsl[]
			org.springframework.cloud.contract.spec.Contract contractDsl = org.springframework.cloud.contract.spec.Contract.make {
				request {
					method 'PUT'
					url '/multipart'
					headers {
						contentType('multipart/form-data;boundary=AaB03x')
					}
					multipart(
							// key (parameter name), value (parameter value) pair
							formParameter: $(c(regex('".+"')), p('"formParameterValue"')),
							someBooleanParameter: $(c(regex(anyBoolean())), p('true')),
							// a named parameter (e.g. with `file` name) that represents file with
							// `name` and `content`. You can also call `named("fileName", "fileContent")`
							file: named(
									// name of the file
									name: $(c(regex(nonEmpty())), p('filename.csv')),
									// content of the file
									content: $(c(regex(nonEmpty())), p('file content')),
									// content type for the part
									contentType: $(c(regex(nonEmpty())), p('application/json')))
					)
				}
				response {
					status OK()
				}
			}
			// end::multipartdsl[]
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			for (String requestString : requestStrings) {
				assert test.contains(requestString)
			}
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder | requestStrings
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 """.param('formParameter', '''"formParameterValue"'''""",
												 """.param('someBooleanParameter', 'true')""",
												 """.multiPart('file', 'filename.csv', 'file content'.bytes, 'application/json')"""]
			"testng"         | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", "filename.csv", "file content".getBytes(), "application/json");']
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", "filename.csv", "file content".getBytes(), "application/json")']
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", "filename.csv", "file content".getBytes(), "application/json")']

	}

	@Issue('180')
	def 'should generate proper test code when having multipart parameters without content type with #methodBuilderName'() {
		given:
			org.springframework.cloud.contract.spec.Contract contractDsl = org.springframework.cloud.contract.spec.Contract.make {
				request {
					method 'PUT'
					url '/multipart'
					headers {
						contentType('multipart/form-data;boundary=AaB03x')
					}
					multipart(
							// key (parameter name), value (parameter value) pair
							formParameter: $(c(regex('".+"')), p('"formParameterValue"')),
							someBooleanParameter: $(c(regex(anyBoolean())), p('true')),
							// a named parameter (e.g. with `file` name) that represents file with
							// `name` and `content`. You can also call `named("fileName", "fileContent")`
							file: named(
									// name of the file
									name: $(c(regex(nonEmpty())), p('filename.csv')),
									// content of the file
									content: $(c(regex(nonEmpty())), p('file content')))
					)
				}
				response {
					status OK()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			for (String requestString : requestStrings) {
				assert test.contains(requestString)
			}
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder | requestStrings
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 """.param('formParameter', '''"formParameterValue"'''""",
												 """.param('someBooleanParameter', 'true')""",
												 """.multiPart('file', 'filename.csv', 'file content'.bytes)"""]
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", "filename.csv", "file content".getBytes());']
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", "filename.csv", "file content".getBytes())']
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", "filename.csv", "file content".getBytes())']
	}

	@Issue('546')
	def 'should generate test code when having multipart parameters with byte array #methodBuilderName'() {
		given:
			// tag::multipartdsl[]
			org.springframework.cloud.contract.spec.Contract contractDsl = org.springframework.cloud.contract.spec.Contract.make {
				request {
					method "PUT"
					url "/multipart"
					headers {
						contentType('multipart/form-data;boundary=AaB03x')
					}
					multipart(
							file: named(
									name: value(stub(regex('.+')), test('file')),
									content: value(stub(regex('.+')), test([100, 117, 100, 97] as byte[]))
							)
					)
				}
				response {
					status 200
				}
			}
			// end::multipartdsl[]
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			for (String requestString : requestStrings) {
				assert test.contains(requestString)
			}
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder | requestStrings
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 """.multiPart('file', 'file', [100, 117, 100, 97] as byte[])"""]
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.multiPart("file", "file", new byte[] {100, 117, 100, 97});']
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.multiPart("file", "file", new byte[] {100, 117, 100, 97});']
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.multiPart("file", "file", new byte[] {100, 117, 100, 97});']

	}

	@Issue('541')
	def 'should generate proper test code when having multipart parameters that use execute with #methodBuilderName'() {
		given:
			org.springframework.cloud.contract.spec.Contract contractDsl = org.springframework.cloud.contract.spec.Contract.make {
				request {
					method "PUT"
					url "/multipart"
					headers {
						contentType('multipart/form-data;boundary=AaB03x')
					}
					multipart(
							formParameter: $(c(regex('".+"')), p('"formParameterValue"')),
							someBooleanParameter: $(c(regex(anyBoolean())), p('true')),
							file: named(
									name: $(c(regex(nonEmpty())), p(execute('toString()'))),
									content: $(c(regex(nonEmpty())), p('file content')))
					)
				}
				response {
					status OK()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			for (String requestString : requestStrings) {
				assert test.contains(requestString)
			}
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder | requestStrings
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 """.param('formParameter', '''"formParameterValue"'''""",
												 """.param('someBooleanParameter', 'true')""",
												 """.multiPart('file', toString(), 'file content'.bytes)"""]
			"testng"         | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", toString(), "file content".getBytes());']
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", toString(), "file content".getBytes())']
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | ['"Content-Type", "multipart/form-data;boundary=AaB03x"',
												 '.param("formParameter", "\\"formParameterValue\\"")',
												 '.param("someBooleanParameter", "true")',
												 '.multiPart("file", toString(), "file content".getBytes())']

	}

	@Issue('180')
	def 'should generate proper test code when having multipart parameters with named as map with #methodBuilderName'() {
		given:
			org.springframework.cloud.contract.spec.Contract contractDsl = org.springframework.cloud.contract.spec.Contract.make {
				request {
					method 'PUT'
					url "/multipart"
					headers {
						contentType('multipart/form-data;boundary=AaB03x')
					}
					multipart(
							// key (parameter name), value (parameter value) pair
							formParameter: $(c(regex('".+"')), p('"formParameterValue"')),
							someBooleanParameter: $(c(regex(anyBoolean())), p('true')),
							// a named parameter (e.g. with `file` name) that represents file with
							// `name` and `content`. You can also call `named("fileName", "fileContent")`
							file: named(
									// name of the file
									name: $(c(regex(nonEmpty())), p('filename.csv')),
									// content of the file
									content: $(c(regex(nonEmpty())), p('file content')))
					)
				}
				response {
					status OK()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('.multiPart')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#216')
	def 'should parse JSON with arrays using Spock'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "GET"
					urlPath('/auth/oauth/check_token') {
						queryParameters {
							parameter 'token': value(
									consumer(regex('^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}')),
									producer('6973b31d-7140-402a-bca6-1cdb954e03a7')
							)
						}
					}
				}
				response {
					status OK()
					body(
							authorities: [
									value(consumer('ROLE_ADMIN'), producer(regex('^[a-zA-Z0-9_\\- ]+$')))
							]
					)
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''assertThatJson(parsedJson).array("[\'authorities']").arrayField().matches("^[a-zA-Z0-9_\\\\- ]+\\$").value()''')
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#216')
	def 'should parse JSON with arrays using JUnit'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method "GET"
					urlPath('/auth/oauth/check_token') {
						queryParameters {
							parameter 'token': value(
									consumer(regex('^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}')),
									producer('6973b31d-7140-402a-bca6-1cdb954e03a7')
							)
						}
					}
				}
				response {
					status OK()
					body(
							authorities: [
									value(consumer('ROLE_ADMIN'), producer(regex('^[a-zA-Z0-9_\\- ]+$')))
							]
					)
				}
			}
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''assertThatJson(parsedJson).array("[\'authorities']").arrayField().matches("^[a-zA-Z0-9_\\\\- ]+$").value()''')
		and:
			SyntaxChecker.tryToCompileJava("mockmvc", test)
	}

	def 'should work with execution property with #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'PUT'
					url '/fraudcheck'
				}
				response {
					status OK()
					body(
							fraudCheckStatus: "OK",
							rejectionReason: $(consumer(null), producer(execute('assertThatRejectionReasonIsNull($it)')))
					)
				}

			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			!test.contains('''assertThatJson(parsedJson).field("[\'rejectionReason']").isEqualTo("assertThatRejectionReasonIsNull("''')
			test.contains('''assertThatRejectionReasonIsNull(''')
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('262')
	def 'should generate proper test code with map inside list'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/foos'
				}
				response {
					status OK()
					body([[id: value(
							consumer('123'),
							producer(regex('[0-9]+'))
					)], [id: value(
							consumer('567'),
							producer(regex('[0-9]+'))
					)]])
					headers {
						contentType(applicationJsonUtf8())
					}
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).array().contains("[\'id\']").matches("[0-9]+")')
		and:
			SyntaxChecker.tryToCompileGroovy("mockmvc", test)
	}

	@Issue('266')
	def 'should generate proper test code with top level array using #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/api/tags'
				}
				response {
					status OK()
					body(["Java", "Java8", "Spring", "SpringBoot", "Stream"])
					headers {
						header('Content-Type': 'application/json;charset=UTF-8')
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).arrayField().contains("Java8").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("Spring").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("Java").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("Stream").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("SpringBoot").value()')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('266')
	@RestoreSystemProperties
	def 'should generate proper test code with top level array using #methodBuilderName with array size check'() {
		given:
			System.setProperty('spring.cloud.contract.verifier.assert.size', 'true')
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/api/tags'
				}
				response {
					status OK()
					body(['Java', 'Java8', 'Spring', 'SpringBoot', 'Stream'])
					headers {
						header('Content-Type': 'application/json;charset=UTF-8')
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).hasSize(5)')
			test.contains('assertThatJson(parsedJson).arrayField().contains("Java8").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("Spring").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("Java").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("Stream").value()')
			test.contains('assertThatJson(parsedJson).arrayField().contains("SpringBoot").value()')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('266')
	def 'should generate proper test code with top level array or arrays using #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/api/categories'
				}
				response {
					status OK()
					body([["Programming", "Java"], ["Programming", "Java", "Spring", "Boot"]])
					headers {
						header('Content-Type': 'application/json;charset=UTF-8')
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).array().array().arrayField().isEqualTo("Programming").value()')
			test.contains('assertThatJson(parsedJson).array().array().arrayField().isEqualTo("Java").value()')
			test.contains('assertThatJson(parsedJson).array().array().arrayField().isEqualTo("Spring").value()')
			test.contains('assertThatJson(parsedJson).array().array().arrayField().isEqualTo("Boot").value()')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('47')
	def 'should generate async body when async flag set in response'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url '/test'
				}
				response {
					status OK()
					async()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains(bodyDefinitionString)
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder                                      | bodyDefinitionString
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | '.when().async()'
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '.when().async()'
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '.when().async()'
	}

	@Issue('372')
	def "should generate async body after queryParams when async flag set in response and queryParams set in request"() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url('/test') {
						queryParameters {
							parameter("param", "value")
						}
					}
				}
				response {
					status OK()
					async()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
			def strippedTest = test.replace('\n', '').replace(' ', '').replaceAll("\t", "").stripIndent().stripMargin()
		then:
			strippedTest.contains('.queryParam("param","value").when().async().get("/test")')
		and:
			stubMappingIsValidWireMockStub(contractDsl)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
	}

	def 'should generate proper test code with array of primitives using #methodBuilderName'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/api/tags'
				}
				response {
					status OK()
					body('''{
							  "partners":[
								  {
									"payment_methods":[ "BANK", "CASH" ]
								  }
							   ]
							}
				''')
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).array("[\'partners\']").array("[\'payment_methods\']").arrayField().isEqualTo("BANK").value()')
			test.contains('assertThatJson(parsedJson).array("[\'partners\']").array("[\'payment_methods\']").arrayField().isEqualTo("CASH").value()')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#273')
	def "should not escape dollar in Spock regex tests"() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/get'
				}
				response {
					status OK()
					body(code: 9, message: $(consumer('Wrong credentials'), producer(regex('^(?!\\s*$).+'))))
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:

			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).field("[\'message\']").matches("^(?!\\\\s*\\$).+")')
		and:
			SyntaxChecker.tryToCompileGroovy("mockmvc", test, false)
	}

	Contract dslForDocs =
			// tag::dsl_example[]
			org.springframework.cloud.contract.spec.Contract.make {
				request {
					method 'PUT'
					url '/api/12'
					headers {
						header 'Content-Type': 'application/vnd.org.springframework.cloud.contract.verifier.twitter-places-analyzer.v1+json'
					}
					body '''\
				[{
					"created_at": "Sat Jul 26 09:38:57 +0000 2014",
					"id": 492967299297845248,
					"id_str": "492967299297845248",
					"text": "Gonna see you at Warsaw",
					"place":
					{
						"attributes":{},
						"bounding_box":
						{
							"coordinates":
								[[
									[-77.119759,38.791645],
									[-76.909393,38.791645],
									[-76.909393,38.995548],
									[-77.119759,38.995548]
								]],
							"type":"Polygon"
						},
						"country":"United States",
						"country_code":"US",
						"full_name":"Washington, DC",
						"id":"01fbe706f872cb32",
						"name":"Washington",
						"place_type":"city",
						"url": "https://api.twitter.com/1/geo/id/01fbe706f872cb32.json"
					}
				}]
			'''
				}
				response {
					status OK()
				}
			}
	// end::dsl_example[]

	Contract dslWithOnlyOneSideForDocs =
			// tag::dsl_one_side_data_generation_example[]
			org.springframework.cloud.contract.spec.Contract.make {
				request {
					method 'PUT'
					url value(consumer(regex('/foo/[0-9]{5}')))
					body([
							requestElement: $(consumer(regex('[0-9]{5}')))
					])
					headers {
						header('header', $(consumer(regex('application\\/vnd\\.fraud\\.v1\\+json;.*'))))
					}
				}
				response {
					status OK()
					body([
							responseElement: $(producer(regex('[0-9]{7}')))
					])
					headers {
						contentType("application/vnd.fraud.v1+json")
					}
				}
			}
	// end::dsl_one_side_data_generation_example[]

	@Issue('#32')
	def 'should generate the regular expression for the other side of communication'() {
		given:
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(dslWithOnlyOneSideForDocs)
			def strippedTest = test.replace('\n', '').stripIndent().stripMargin()
		then:
			strippedTest.matches(""".*header\\("header", "application\\/vnd\\.fraud\\.v1\\+json;.*"\\).*""")
			strippedTest.matches(""".*body\\('''\\{"requestElement":"[0-9]{5}"\\}'''\\).*""")
			strippedTest.matches(""".*put\\("/foo/[0-9]{5}"\\).*""")
			strippedTest.contains("""response.header("Content-Type") ==~ java.util.regex.Pattern.compile('application/vnd\\\\.fraud\\\\.v1\\\\+json.*')""")
			"application/vnd.fraud.v1+json;charset=UTF-8".matches('application/vnd\\.fraud\\.v1\\+json.*')
			strippedTest.contains("""assertThatJson(parsedJson).field("['responseElement']").matches("[0-9]{7}")""")
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#85')
	def 'should execute custom method for complex structures on the response side'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/get'
				}
				response {
					status OK()
					body([
							fraudCheckStatus: "OK",
							rejectionReason : [
									title: $(consumer(null), producer(execute('assertThatRejectionReasonIsNull($it)')))
							]
					])
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatRejectionReasonIsNull(parsedJson.read("\\$.rejectionReason.title"))')
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#85')
	def 'should execute custom method for more complex structures on the response side when using Spock'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/get'
				}
				response {
					status OK()
					body([
							[
									name: $(consumer("userName 1"), producer(execute('assertThatUserNameIsNotNull($it)')))
							],
							[
									name: $(consumer("userName 2"), producer(execute('assertThatUserNameIsNotNull($it)')))
							]
					])
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''assertThatUserNameIsNotNull(parsedJson.read("\\$.[0].name")''')
			test.contains('''assertThatUserNameIsNotNull(parsedJson.read("\\$.[1].name")''')
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#85')
	def 'should execute custom method for more complex structures on the response side when using JUnit'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/get'
				}
				response {
					status OK()
					body([
							[
									name: $(consumer("userName 1"), producer(execute('assertThatUserNameIsNotNull($it)')))
							],
							[
									name: $(consumer("userName 2"), producer(execute('assertThatUserNameIsNotNull($it)')))
							]
					])
				}
			}
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('''assertThatUserNameIsNotNull(parsedJson.read("$.[0].name")''')
			test.contains('''assertThatUserNameIsNotNull(parsedJson.read("$.[1].name")''')
	}

	@Issue('#111')
	def 'should execute custom method for request headers'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/get'
					headers {
						header('authorization', value(consumer('Bearer token'), producer(execute('getOAuthTokenHeader()'))))
					}
				}
				response {
					status OK()
					body([
							fraudCheckStatus: "OK",
							rejectionReason : [
									title: $(consumer(null), producer(execute('assertThatRejectionReasonIsNull($it)')))
							]
					])
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('.header("authorization", getOAuthTokenHeader())')
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#150')
	def 'should support body matching in response'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url '/get'
				}
				response {
					status OK()
					body(value(stub("HELLO FROM STUB"), server(regex(".*"))))
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:

			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("responseBody ==~ java.util.regex.Pattern.compile('.*')")
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#150')
	def 'should support custom method execution in response'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url '/get'
				}
				response {
					status OK()
					body(value(stub("HELLO FROM STUB"), server(execute('foo($it)'))))
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:

			String test = singleTestGenerator(contractDsl)
		then:
			test.contains("foo(responseBody)")
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#149')
	def 'should allow c/p version of consumer producer'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/get'
					headers {
						header('authorization', $(c('Bearer token'), p(execute('getOAuthTokenHeader()'))))
					}
				}
				response {
					status OK()
					body([
							fraudCheckStatus: "OK",
							rejectionReason : [
									title: $(c(null), p(execute('assertThatRejectionReasonIsNull($it)')))
							]
					])
				}
			}
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('.header("authorization", getOAuthTokenHeader())')
		and:
			SyntaxChecker.tryToCompileGroovy("spock", test)
	}

	@Issue('#149')
	def 'should allow easier way of providing dynamic values for [#methodBuilderName]'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath '/get'
					body([
							duck             : $(regex("[0-9]")),
							alpha            : $(anyAlphaUnicode()),
							number           : $(anyNumber()),
							anInteger        : $(anyInteger()),
							positiveInt      : $(positiveInt()),
							aDouble          : $(anyDouble()),
							aBoolean         : $(aBoolean()),
							ip               : $(anyIpAddress()),
							hostname         : $(anyHostname()),
							email            : $(anyEmail()),
							url              : $(anyUrl()),
							httpsUrl         : $(anyHttpsUrl()),
							uuid             : $(anyUuid()),
							date             : $(anyDate()),
							dateTime         : $(anyDateTime()),
							time             : $(anyTime()),
							iso8601WithOffset: $(anyIso8601WithOffset()),
							nonBlankString   : $(anyNonBlankString()),
							nonEmptyString   : $(anyNonEmptyString()),
							anyOf            : $(anyOf('foo', 'bar'))
					])
					headers {
						contentType(applicationJson())
					}
				}
				response {
					status OK()
					body([
							alpha            : $(anyAlphaUnicode()),
							number           : $(anyNumber()),
							anInteger        : $(anyInteger()),
							positiveInt      : $(positiveInt()),
							aDouble          : $(anyDouble()),
							aBoolean         : $(aBoolean()),
							ip               : $(anyIpAddress()),
							hostname         : $(anyHostname()),
							email            : $(anyEmail()),
							url              : $(anyUrl()),
							httpsUrl         : $(anyHttpsUrl()),
							uuid             : $(anyUuid()),
							date             : $(anyDate()),
							dateTime         : $(anyDateTime()),
							time             : $(anyTime()),
							iso8601WithOffset: $(anyIso8601WithOffset()),
							nonBlankString   : $(anyNonBlankString()),
							nonEmptyString   : $(anyNonEmptyString()),
							anyOf            : $(anyOf('foo', 'bar'))
					])
					headers {
						contentType(applicationJson())
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).field("[\'aBoolean\']").matches("(true|false)")')
			test.contains('assertThatJson(parsedJson).field("[\'alpha\']").matches("[\\\\p{L}]*")')
			test.contains('assertThatJson(parsedJson).field("[\'hostname\']").matches("((http[s]?|ftp):/)/?([^:/\\\\s]+)(:[0-9]{1,5})?")')
			test.contains('assertThatJson(parsedJson).field("[\'number\']").matches("-?(\\\\d*\\\\.\\\\d+|\\\\d+)")')
			test.contains('assertThatJson(parsedJson).field("[\'anInteger\']").matches("-?(\\\\d+)")')
			test.contains('assertThatJson(parsedJson).field("[\'positiveInt\']").matches("([1-9]\\\\d*)")')
			test.contains('assertThatJson(parsedJson).field("[\'aDouble\']").matches("-?(\\\\d*\\\\.\\\\d+)")')
			test.contains('assertThatJson(parsedJson).field("[\'email\']").matches("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,6}")')
			test.contains('assertThatJson(parsedJson).field("[\'ip\']").matches("([01]?\\\\d\\\\d?|2[0-4]\\\\d|25[0-5])\\\\.([01]?\\\\d\\\\d?|2[0-4]\\\\d|25[0-5])\\\\.([01]?\\\\d\\\\d?|2[0-4]\\\\d|25[0-5])\\\\.([01]?\\\\d\\\\d?|2[0-4]\\\\d|25[0-5])")')
			test.contains('assertThatJson(parsedJson).field("[\'url\']").matches("^(?:(?:[A-Za-z][+-.\\\\w^_]*:/{2})?(?:\\\\S+(?::\\\\S*)?@)?(?:(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|(?:(?:[a-z\\\\u00a1-\\\\uffff0-9]-*)*[a-z\\\\u00a1-\\\\uffff0-9]+)(?:\\\\.(?:[a-z\\\\u00a1-\\\\uffff0-9]-*)*[a-z\\\\u00a1-\\\\uffff0-9]+)*(?:\\\\.(?:[a-z\\\\u00a1-\\\\uffff]{2,}))|(?:localhost))(?::\\\\d{2,5})?(?:/\\\\S*)?)')
			test.contains('assertThatJson(parsedJson).field("[\'httpsUrl\']").matches("^(?:https:/{2}(?:\\\\S+(?::\\\\S*)?@)?(?:(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|(?:(?:[a-z\\\\u00a1-\\\\uffff0-9]-*)*[a-z\\\\u00a1-\\\\uffff0-9]+)(?:\\\\.(?:[a-z\\\\u00a1-\\\\uffff0-9]-*)*[a-z\\\\u00a1-\\\\uffff0-9]+)*(?:\\\\.(?:[a-z\\\\u00a1-\\\\uffff]{2,}))|(?:localhost))(?::\\\\d{2,5})?(?:/\\\\S*)?)')
			test.contains('assertThatJson(parsedJson).field("[\'uuid\']").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")')
			test.contains('assertThatJson(parsedJson).field("[\'date\']").matches("(\\\\d\\\\d\\\\d\\\\d)-(0[1-9]|1[012])-(0[1-9]|[12][0-9]|3[01])')
			test.contains('assertThatJson(parsedJson).field("[\'dateTime\']").matches("([0-9]{4})-(1[0-2]|0[1-9])-(3[01]|0[1-9]|[12][0-9])T(2[0-3]|[01][0-9]):([0-5][0-9]):([0-5][0-9])')
			test.contains('assertThatJson(parsedJson).field("[\'time\']").matches("(2[0-3]|[01][0-9]):([0-5][0-9]):([0-5][0-9])")')
			test.contains('assertThatJson(parsedJson).field("[\'iso8601WithOffset\']").matches("([0-9]{4})-(1[0-2]|0[1-9])-(3[01]|0[1-9]|[12][0-9])T(2[0-3]|[01][0-9]):([0-5][0-9]):([0-5][0-9])(\\\\.\\\\d{3})?(Z|[+-][01]\\\\d:[0-5]\\\\d)")')
			test.contains('assertThatJson(parsedJson).field("[\'nonBlankString\']").matches("^\\\\s*\\\\S[\\\\S\\\\s]*")')
			test.contains('assertThatJson(parsedJson).field("[\'nonEmptyString\']").matches("[\\\\S\\\\s]+")')
			test.contains('assertThatJson(parsedJson).field("[\'anyOf\']").matches("^foo' + endOfLineRegExSymbol + '|^bar' + endOfLineRegExSymbol + '")')
			!test.contains('cursor')
			!test.contains('REGEXP>>')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		and:
			String jsonSample = '''\
String json = "{\\"duck\\":\\"8\\",\\"alpha\\":\\"YAJEOWYGMFBEWPMEMAZI\\",\\"number\\":-2095030871,\\"anInteger\\":1780305902,\\"positiveInt\\":345,\\"aDouble\\":42.345,\\"aBoolean\\":true,\\"ip\\":\\"129.168.99.100\\",\\"hostname\\":\\"https://foo389886219.com\\",\\"email\\":\\"foo@bar1367573183.com\\",\\"url\\":\\"https://foo-597104692.com\\",\\"httpsUrl\\":\\"https://baz-486093581.com\\",\\"uuid\\":\\"e436b817-b764-49a2-908e-967f2f99eb9f\\",\\"date\\":\\"2014-04-14\\",\\"dateTime\\":\\"2011-01-11T12:23:34\\",\\"time\\":\\"12:20:30\\",\\"iso8601WithOffset\\":\\"2015-05-15T12:23:34.123Z\\",\\"nonBlankString\\":\\"EPZWVIRHSUAPBJMMQSFO\\",\\"nonEmptyString\\":\\"RVMFDSEQFHRQFVUVQPIA\\",\\"anyOf\\":\\"foo\\"}";
DocumentContext parsedJson = JsonPath.parse(json);
'''
		where:
			methodBuilderName | methodBuilder                                      | endOfLineRegExSymbol
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | '\\$'
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '$'
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '$'
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '$'
	}

	@Issue('#162')
	def 'should escape regex properly for content type'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method GET()
					url 'get'
					headers {
						contentType("application/vnd.fraud.v1+json")
					}
				}
				response {
					status OK()
					headers {
						contentType("application/vnd.fraud.v1+json")
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('application/vnd\\\\.fraud\\\\.v1\\\\+json.*')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#173')
	def 'should resolve Optional object when used in query parameters'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					urlPath('/blacklist') {
						queryParameters {
							parameter 'isActive': value(consumer(optional(regex('(true|false)'))))
							parameter 'limit': value(consumer(optional(regex('([0-9]{1,10})'))))
							parameter 'offset': value(consumer(optional(regex('([0-9]{1,10})'))))
						}
					}
					headers {
						header 'Content-Type': 'application/json'
					}
				}
				response {
					status(200)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			!test.contains('org.springframework.cloud.contract.spec.internal.OptionalProperty')
			test.contains('(([0-9]{1,10}))?')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#172')
	def 'should resolve plain text properly via headers'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url("/foo")
				}
				response {
					status(200)
					body '{"a":1}\n{"a":2}'
					headers {
						contentType(textPlain())
					}
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			!test.contains('assertThatJson(parsedJson).field("[\'a\']").isEqualTo(1)')
			test.contains(expectedAssertion)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			//order is inverted cause Intellij didn't parse this properly
			methodBuilderName | methodBuilder                                      | expectedAssertion
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | """responseBody == '''{"a":1}\\n{"a":2}'''"""
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '''assertThat(responseBody).isEqualTo("{\\"a\\":1}\\n{\\"a\\":2}'''
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '''assertThat(responseBody).isEqualTo("{\\"a\\":1}\\n{\\"a\\":2}'''
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '''assertThat(responseBody).isEqualTo("{\\"a\\":1}\\n{\\"a\\":2}'''
	}

	@Issue('#443')
	def "should resolve plain text that happens to be a valid json for [#methodBuilderName]"() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url '/foo'
				}
				response {
					status OK()
					headers {
						contentType(applicationJsonUtf8())
					}
					body(
							value(client('true'), server(regex("true|false")))
					)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			testAssertion(test)
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder | testAssertion
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | { String testContents -> testContents.contains("""responseBody ==~ java.util.regex.Pattern.compile('true|false')""") }
			"testng"         | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | { String testContents -> testContents.contains("""assertThat(responseBody).matches("true|false");""") }
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | { String testContents -> testContents.contains("""assertThat(responseBody).matches("true|false");""") }
			"jaxrs-spock"     | {
				properties.testFramework = TestFramework.SPOCK; properties.testMode = TestMode.JAXRSCLIENT
			}                                 | { String testContents -> testContents.contains("""responseBody ==~ java.util.regex.Pattern.compile("true|false")""") }
			"jaxrs"           | {
				properties.testFramework = TestFramework.JUNIT; properties.testMode = TestMode.JAXRSCLIENT
			}                                 | { String testContents -> testContents.contains("""assertThat(responseBody).matches("true|false");""") }
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | { String testContents -> testContents.contains("""assertThat(responseBody).matches("true|false");""") }
	}

	@Issue('#169')
	def 'should escape quotes properly using [#methodBuilderName]'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'POST'
					url '/foo'
					body(
							xyz: 'abc'
					)
					headers { header('Content-Type', 'application/json;charset=UTF-8') }
				}
				response {
					status OK()
					body(
							bar: $(producer(regex('some value \u0022with quote\u0022|bar')))
					)
					headers { header('Content-Type': 'application/json;charset=UTF-8') }
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			test.contains('assertThatJson(parsedJson).field("[\'bar\']").matches("some value \\"with quote\\"|bar")')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			//order is inverted cause Intellij didn't parse this properly
			methodBuilderName | methodBuilder                                      | expectedAssertion
			"spock"           | { properties.testFramework = TestFramework.SPOCK } | '''responseBody == "{\\"a\\":1}\\n{\\"a\\":2}"'''
			"testng"          | { properties.testFramework = TestFramework.TESTNG }| '''assertThat(responseBody).isEqualTo("{\\"a\\":1}\\n{\\"a\\":2}'''
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }         | '''assertThat(responseBody).isEqualTo("{\\"a\\":1}\\n{\\"a\\":2}'''
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }   | '''assertThat(responseBody).isEqualTo("{\\"a\\":1}\\n{\\"a\\":2}'''
	}

	@Issue('#169')
	def 'should make the execute method work in a url for [#methodBuilderName]'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'POST'
					url $(c("foo"), p(execute("toString()")))
				}
				response {
					status OK()
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		and:
			test.contains("toString()")
			!test.contains("\"toString()\"")
			!test.contains("'toString()'")
		where:
			methodBuilderName | methodBuilder
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}
			"jaxrs-spock"     | {
				properties.testFramework = TestFramework.SPOCK; properties.testMode = TestMode.JAXRSCLIENT
			}
			"jaxrs"           | {
				properties.testFramework = TestFramework.JUNIT; properties.testMode = TestMode.JAXRSCLIENT
			}
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}
	}

	@Issue('#203')
	def 'should create an assertion for an empty list for [#methodBuilderName]'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url '/api/v1/xxxx'
				}
				response {
					status OK()
					body([
							status: '200',
							list  : [],
							foo   : ["bar", "baz"]
					])
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
			test.contains('assertThatJson(parsedJson).array("[\'list\']").isEmpty()')
			!test.contains('assertThatJson(parsedJson).array("[\'foo\']").isEmpty()')
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"jaxrs-spock"     | {
				properties.testFramework = TestFramework.SPOCK; properties.testMode = TestMode.JAXRSCLIENT
			}
			"jaxrs"           | {
				properties.testFramework = TestFramework.JUNIT; properties.testMode = TestMode.JAXRSCLIENT
			}
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	@Issue('#226')
	def 'should work properly when body is an integer [#methodBuilderName]'() {
		given:
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url '/api/v1/xxxx'
					body(12000)
				}
				response {
					status OK()
					body(12000)
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
			requestAssertion(test)
			responseAssertion(test)
		where:
			methodBuilderName | methodBuilder | requestAssertion                                                                             | responseAssertion
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | { String body -> body.contains("body('''12000''')") }                                        | { String body -> body.contains("responseBody == '12000'") }
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | { String body -> body.contains('body("12000")') }                                            | { String body -> body.contains('assertThat(responseBody).isEqualTo("12000");') }
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | { String body -> body.contains('body("12000")') }                                            | { String body -> body.contains('assertThat(responseBody).isEqualTo("12000");') }
			"jaxrs-spock"     | {
				properties.testFramework = TestFramework.SPOCK; properties.testMode = TestMode.JAXRSCLIENT
			}                                 | { String body -> body.contains(""".build("GET", entity("12000", "text/plain"))""") } | { String body -> body.contains('responseBody == "12000"') }
			"jaxrs"           | {
				properties.testFramework = TestFramework.JUNIT; properties.testMode = TestMode.JAXRSCLIENT
			}                                 | { String body -> body.contains(""".build("GET", entity("12000", "text/plain"))""") } | { String body -> body.contains('assertThat(responseBody).isEqualTo("12000")') }
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | { String body -> body.contains('body("12000")') }                                            | { String body -> body.contains('assertThat(responseBody).isEqualTo("12000");') }
	}

	@Issue('#230')
	def 'should manage to reference request in response [#methodBuilderName]'() {
		given:
			//tag::template_contract[]
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url('/api/v1/xxxx') {
						queryParameters {
							parameter('foo', 'bar')
							parameter('foo', 'bar2')
						}
					}
					headers {
						header(authorization(), 'secret')
						header(authorization(), 'secret2')
					}
					body(foo: 'bar', baz: 5)
				}
				response {
					status OK()
					headers {
						header(authorization(), "foo ${fromRequest().header(authorization())} bar")
					}
					body(
							url: fromRequest().url(),
							path: fromRequest().path(),
							pathIndex: fromRequest().path(1),
							param: fromRequest().query('foo'),
							paramIndex: fromRequest().query('foo', 1),
							authorization: fromRequest().header('Authorization'),
							authorization2: fromRequest().header('Authorization', 1),
							fullBody: fromRequest().body(),
							responseFoo: fromRequest().body('$.foo'),
							responseBaz: fromRequest().body('$.baz'),
							responseBaz2: "Bla bla ${fromRequest().body('$.foo')} bla bla",
							rawUrl: fromRequest().rawUrl(),
							rawPath: fromRequest().rawPath(),
							rawPathIndex: fromRequest().rawPath(1),
							rawParam: fromRequest().rawQuery('foo'),
							rawParamIndex: fromRequest().rawQuery('foo', 1),
							rawAuthorization: fromRequest().rawHeader('Authorization'),
							rawAuthorization2: fromRequest().rawHeader('Authorization', 1),
							rawResponseFoo: fromRequest().rawBody('$.foo'),
							rawResponseBaz: fromRequest().rawBody('$.baz'),
							rawResponseBaz2: "Bla bla ${fromRequest().rawBody('$.foo')} bla bla"
					)
				}
			}
			//end::template_contract[]
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			SyntaxChecker.tryToCompileWithoutCompileStatic(methodBuilderName, test)
			!test.contains('''DslProperty''')
			!test.contains('''ERROR: ''')
			test.contains('''assertThatJson(parsedJson).field("['url']").isEqualTo("/api/v1/xxxx?foo=bar&foo=bar2")''')
			test.contains('''assertThatJson(parsedJson).field("['path']").isEqualTo("/api/v1/xxxx")''')
			test.contains('''assertThatJson(parsedJson).field("['pathIndex']").isEqualTo("v1")''')
			test.contains('''assertThatJson(parsedJson).field("['fullBody']").isEqualTo("{\\"foo\\":\\"bar\\",\\"baz\\":5}")''')
			test.contains('''assertThatJson(parsedJson).field("['paramIndex']").isEqualTo("bar2")''')
			test.contains('''assertThatJson(parsedJson).field("['responseFoo']").isEqualTo("bar")''')
			test.contains('''assertThatJson(parsedJson).field("['authorization']").isEqualTo("secret")''')
			test.contains('''assertThatJson(parsedJson).field("['authorization2']").isEqualTo("secret2")''')
			test.contains('''assertThatJson(parsedJson).field("['responseBaz']").isEqualTo(5)''')
			test.contains('''assertThatJson(parsedJson).field("['responseBaz2']").isEqualTo("Bla bla bar bla bla")''')
			test.contains('''assertThatJson(parsedJson).field("['param']").isEqualTo("bar")''')
			test.contains('''assertThatJson(parsedJson).field("['rawUrl']").isEqualTo("/api/v1/xxxx?foo=bar&foo=bar2")''')
			test.contains('''assertThatJson(parsedJson).field("['rawPath']").isEqualTo("/api/v1/xxxx")''')
			test.contains('''assertThatJson(parsedJson).field("['rawPathIndex']").isEqualTo("v1")''')
			test.contains('''assertThatJson(parsedJson).field("['rawParamIndex']").isEqualTo("bar2")''')
			test.contains('''assertThatJson(parsedJson).field("['rawResponseFoo']").isEqualTo("bar")''')
			test.contains('''assertThatJson(parsedJson).field("['rawAuthorization']").isEqualTo("secret")''')
			test.contains('''assertThatJson(parsedJson).field("['rawAuthorization2']").isEqualTo("secret2")''')
			test.contains('''assertThatJson(parsedJson).field("['rawResponseBaz']").isEqualTo(5)''')
			test.contains('''assertThatJson(parsedJson).field("['rawResponseBaz2']").isEqualTo("Bla bla bar bla bla")''')
			test.contains('''assertThatJson(parsedJson).field("['rawParam']").isEqualTo("bar")''')
			responseAssertion(test)
		where:
			methodBuilderName | methodBuilder | responseAssertion
			"spock"           | {
				properties.testFramework = TestFramework.SPOCK
			}                                 | { String body -> body.contains("""response.header("Authorization") == 'foo secret bar'""") }
			"testng"          | {
				properties.testFramework = TestFramework.TESTNG
			}                                 | { String body -> body.contains('assertThat(response.header("Authorization")).isEqualTo("foo secret bar");') }
			"mockmvc"         | {
				properties.testMode = TestMode.MOCKMVC
			}                                 | { String body -> body.contains('assertThat(response.header("Authorization")).isEqualTo("foo secret bar");') }
			"jaxrs-spock"     | {
				properties.testFramework = TestFramework.SPOCK; properties.testMode = TestMode.JAXRSCLIENT
			}                                 | { String body -> body.contains('''response.getHeaderString("Authorization") == "foo secret bar"''') }
			"jaxrs"           | {
				properties.testFramework = TestFramework.JUNIT; properties.testMode = TestMode.JAXRSCLIENT
			}                                 | { String body -> body.contains('assertThat(response.getHeaderString("Authorization")).isEqualTo("foo secret bar");') }
			"webclient"       | {
				properties.testMode = TestMode.WEBTESTCLIENT
			}                                 | { String body -> body.contains('''assertThat(response.header("Authorization")).isEqualTo("foo secret bar");''') }
	}

	@Issue('#230')
	def 'should manage to reference request in response via WireMock native entries [#methodBuilderName]'() {
		given:
			//tag::template_contract[]
			Contract contractDsl = Contract.make {
				request {
					method 'GET'
					url('/api/v1/xxxx') {
						queryParameters {
							parameter('foo', 'bar')
							parameter('foo', 'bar2')
						}
					}
					headers {
						header(authorization(), 'secret')
						header(authorization(), 'secret2')
					}
					body(foo: "bar", baz: 5)
				}
				response {
					status OK()
					headers {
						contentType(applicationJson())
					}
					body(''' 
							{
								"responseFoo": "{{{ jsonPath request.body '$.foo' }}}",
								"responseBaz": {{{ jsonPath request.body '$.baz' }}},
								"responseBaz2": "Bla bla {{{ jsonPath request.body '$.foo' }}} bla bla"
							}
					'''.toString())
				}
			}
			//end::template_contract[]
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			SyntaxChecker.tryToCompileWithoutCompileStatic(methodBuilderName, test)
			!test.contains('''DslProperty''')
			test.contains('''assertThatJson(parsedJson).field("['responseFoo']").isEqualTo("bar")''')
			test.contains('''assertThatJson(parsedJson).field("['responseBaz']").isEqualTo(5)''')
			test.contains('''assertThatJson(parsedJson).field("['responseBaz2']").isEqualTo("Bla bla bar bla bla")''')
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"jaxrs-spock"     | {
				properties.testFramework = TestFramework.SPOCK; properties.testMode = TestMode.JAXRSCLIENT
			}
			"jaxrs"           | {
				properties.testFramework = TestFramework.JUNIT; properties.testMode = TestMode.JAXRSCLIENT
			}
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate JUnit assertions with cookies [#methodBuilderName]'() {
		given:
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDslWithCookiesValue)
		then:
			test.contains('''.cookie("cookie-key", "cookie-value")''')
			test.contains('''assertThat(response.cookie("cookie-key")).isNotNull();''')
			test.contains('''assertThat(response.cookie("cookie-key")).isEqualTo("new-cookie-value");''')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate JUnit assertions with cookies pattern [#methodBuilderName]'() {
		given:
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDslWithCookiesPattern)
		then:
			!test.contains('''.cookie("cookie-key", "[A-Za-z]+")''')
			test.contains('''.cookie("cookie-key", "''')
			test.contains('''assertThat(response.cookie("cookie-key")).isNotNull();''')
			test.contains('''assertThat(response.cookie("cookie-key")).matches("[A-Za-z]+");''')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should not generate JUnit cookie assertion with absent cookie [#methodBuilderName]'() {
		given:
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDslWithAbsentCookies)
		then:
			!test.contains('cookie')
		and:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
		where:
			methodBuilderName | methodBuilder
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}

	def 'should generate spock assertions with cookies'() {
		given:
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDslWithCookiesValue)
		then:
			test.contains('''.cookie("cookie-key", "cookie-value")''')
			test.contains('''response.cookie("cookie-key") != null''')
			test.contains('''response.cookie("cookie-key") == 'new-cookie-value''')
		and:
			SyntaxChecker.tryToCompile("spock", test)
	}

	def 'should generate spock assertions with cookies pattern'() {
		given:
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDslWithCookiesPattern)
		then:
			!test.contains('''.cookie("cookie-key", "[A-Za-z]+")''')
			test.contains('''.cookie("cookie-key", "''')
			test.contains('''response.cookie("cookie-key") != null''')
			test.contains('''response.cookie("cookie-key") ==~ java.util.regex.Pattern.compile('[A-Za-z]+')''')
		and:
			SyntaxChecker.tryToCompile("spock", test)
	}

	def 'should not generate spock cookie assertion with absent cookie'() {
		given:
			properties.testFramework = TestFramework.SPOCK
		when:
			String test = singleTestGenerator(contractDslWithAbsentCookies)
		then:
			!test.contains("cookie")
		and:
			SyntaxChecker.tryToCompile("spock", test)
	}

	@Issue('#554')
	def 'should create an assertion for an empty map or Object for [#methodBuilderName]'() {
		given:
			Contract contractDsl = Contract.make {
				name("method")
				request {
					method 'GET'
					url '/api/v1/xxxx'
				}
				response {
					status 200
					body([
							aMap      : ["foo": "bar"],
							anEmptyMap: [:]
					])
				}
			}
			methodBuilder()
		when:
			String test = singleTestGenerator(contractDsl)
		then:
			SyntaxChecker.tryToCompile(methodBuilderName, test)
			test.contains('''assertThatJson(parsedJson).field("['aMap']").field("['foo']").isEqualTo("bar")''')
			test.contains('''assertThatJson(parsedJson).field("['anEmptyMap']").isEmpty()''')
		where:
			methodBuilderName | methodBuilder
			"spock"           | { properties.testFramework = TestFramework.SPOCK }
			"testng"          | { properties.testFramework = TestFramework.TESTNG }
			"mockmvc"         | { properties.testMode = TestMode.MOCKMVC }
			"jaxrs-spock"     | {
				properties.testFramework = TestFramework.SPOCK; properties.testMode = TestMode.JAXRSCLIENT
			}
			"jaxrs"           | {
				properties.testFramework = TestFramework.JUNIT; properties.testMode = TestMode.JAXRSCLIENT
			}
			"webclient"       | { properties.testMode = TestMode.WEBTESTCLIENT }
	}
}
