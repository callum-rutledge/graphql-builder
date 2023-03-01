package com.fleetpin.graphql.builder;

import com.fleetpin.graphql.builder.annotations.Entity;
import com.fleetpin.graphql.builder.annotations.GraphQLDescription;
import com.fleetpin.graphql.builder.annotations.GraphQLIgnore;
import com.fleetpin.graphql.builder.annotations.InputIgnore;
import com.fleetpin.graphql.builder.annotations.OneOf;
import com.fleetpin.graphql.builder.annotations.SchemaOption;
import com.fleetpin.graphql.builder.mapper.InputTypeBuilder;
import com.fleetpin.graphql.builder.mapper.ObjectFieldBuilder;
import com.fleetpin.graphql.builder.mapper.ObjectFieldBuilder.FieldMapper;
import com.fleetpin.graphql.builder.mapper.OneOfBuilder;
import com.fleetpin.graphql.builder.mapper.RecordFieldBuilder;
import com.fleetpin.graphql.builder.mapper.RecordFieldBuilder.RecordMapper;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputObjectType.Builder;
import graphql.schema.GraphQLNamedInputType;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

public abstract class InputBuilder {

	protected final EntityProcessor entityProcessor;
	protected final TypeMeta meta;

	public InputBuilder(EntityProcessor entityProcessor, TypeMeta meta) {
		this.entityProcessor = entityProcessor;
		this.meta = meta;
	}

	protected String buildName() {
		var schemaType = SchemaOption.BOTH;
		Entity graphTypeAnnotation = meta.getType().getAnnotation(Entity.class);
		if (graphTypeAnnotation != null) {
			schemaType = graphTypeAnnotation.value();
		}

		String typeName = EntityUtil.getName(meta);

		String inputName = typeName;
		if (schemaType != SchemaOption.INPUT) {
			inputName += "Input";
		}
		return inputName;
	}

	public GraphQLNamedInputType buildInput() {
		GraphQLInputObjectType.Builder graphInputType = GraphQLInputObjectType.newInputObject();
		var type = meta.getType();

		graphInputType.name(buildName());

		{
			var description = type.getAnnotation(GraphQLDescription.class);
			if (description != null) {
				graphInputType.description(description.value());
			}
		}

		processFields(graphInputType);

		entityProcessor.addSchemaDirective(type, type, graphInputType::withAppliedDirective);
		return graphInputType.build();
	}

	abstract void processFields(Builder graphInputType);

	protected abstract InputTypeBuilder resolve();

	public static class OneOfInputBuilder extends InputBuilder {

		public OneOfInputBuilder(EntityProcessor entityProcessor, TypeMeta meta) {
			super(entityProcessor, meta);
		}

		@Override
		void processFields(Builder graphInputType) {
			var oneOf = meta.getType().getAnnotation(OneOf.class);
			for (var oneOfType : oneOf.value()) {
				var name = oneOfType.name();
				GraphQLInputObjectField.Builder field = GraphQLInputObjectField.newInputObjectField();
				field.name(name);
				TypeMeta innerMeta = new TypeMeta(meta, oneOfType.type(), oneOfType.type());
				innerMeta.optional();
				var type = entityProcessor.getEntity(innerMeta).getInputType(innerMeta, new Annotation[0]);
				field.type(type);
				graphInputType.field(field);
			}
		}

		@Override
		protected InputTypeBuilder resolve() {
			var oneOf = meta.getType().getAnnotation(OneOf.class);
			return new OneOfBuilder(entityProcessor, meta.getType(), oneOf);
		}
	}

	public static class ObjectType extends InputBuilder {

		public ObjectType(EntityProcessor entityProcessor, TypeMeta meta) {
			super(entityProcessor, meta);
		}

		@Override
		void processFields(Builder graphInputType) {
			for (Method method : meta.getType().getMethods()) {
				try {
					if (method.isSynthetic()) {
						continue;
					}
					if (method.getDeclaringClass().equals(Object.class)) {
						continue;
					}
					if (method.isAnnotationPresent(GraphQLIgnore.class)) {
						continue;
					}
					//will also be on implementing class
					if (Modifier.isAbstract(method.getModifiers()) || method.getDeclaringClass().isInterface()) {
						continue;
					}
					if (Modifier.isStatic(method.getModifiers())) {
						continue;
					} else {
						//getter type
						if (method.getName().matches("set[A-Z].*")) {
							if (method.getParameterCount() == 1 && !method.isAnnotationPresent(InputIgnore.class)) {
								String name =
									method.getName().substring("set".length(), "set".length() + 1).toLowerCase() +
									method.getName().substring("set".length() + 1);
								GraphQLInputObjectField.Builder field = GraphQLInputObjectField.newInputObjectField();
								field.name(name);
								entityProcessor.addSchemaDirective(method, meta.getType(), field::withAppliedDirective);
								TypeMeta innerMeta = new TypeMeta(meta, method.getParameterTypes()[0], method.getGenericParameterTypes()[0]);
								var entity = entityProcessor.getEntity(innerMeta);
								var inputType = entity.getInputType(innerMeta, method.getParameterAnnotations()[0]);
								field.type(inputType);
								graphInputType.field(field);
							}
						}
					}
				} catch (RuntimeException e) {
					throw new RuntimeException("Failed to process method " + method, e);
				}
			}
		}

		@Override
		public InputTypeBuilder resolve() {
			var fieldMappers = new ArrayList<FieldMapper>();

			for (Method method : meta.getType().getMethods()) {
				try {
					if (method.isSynthetic()) {
						continue;
					}
					if (method.getDeclaringClass().equals(Object.class)) {
						continue;
					}
					if (method.isAnnotationPresent(GraphQLIgnore.class)) {
						continue;
					}
					//will also be on implementing class
					if (Modifier.isAbstract(method.getModifiers()) || method.getDeclaringClass().isInterface()) {
						continue;
					}
					if (Modifier.isStatic(method.getModifiers())) {
						continue;
					} else {
						//getter type
						if (method.getName().matches("set[A-Z].*")) {
							if (method.getParameterCount() == 1 && !method.isAnnotationPresent(InputIgnore.class)) {
								String name =
									method.getName().substring("set".length(), "set".length() + 1).toLowerCase() +
									method.getName().substring("set".length() + 1);
								TypeMeta innerMeta = new TypeMeta(meta, method.getParameterTypes()[0], method.getGenericParameterTypes()[0]);
								fieldMappers.add(FieldMapper.build(entityProcessor, innerMeta, name, method));
							}
						}
					}
				} catch (RuntimeException e) {
					throw new RuntimeException("Failed to process method " + method, e);
				}
			}

			return new ObjectFieldBuilder(meta.getType(), fieldMappers);
		}
	}

	public static class Record extends InputBuilder {

		public Record(EntityProcessor entityProcessor, TypeMeta meta) {
			super(entityProcessor, meta);
		}

		@Override
		void processFields(Builder graphInputType) {
			for (var field : this.meta.getType().getDeclaredFields()) {
				try {
					if (field.isSynthetic()) {
						continue;
					}
					if (field.isAnnotationPresent(GraphQLIgnore.class)) {
						continue;
					}
					if (Modifier.isStatic(field.getModifiers())) {
						continue;
					} else {
						//getter type
						if (!field.isAnnotationPresent(InputIgnore.class)) {
							String name = field.getName();
							GraphQLInputObjectField.Builder fieldBuilder = GraphQLInputObjectField.newInputObjectField();
							fieldBuilder.name(name);
							entityProcessor.addSchemaDirective(field, meta.getType(), fieldBuilder::withAppliedDirective);
							TypeMeta innerMeta = new TypeMeta(meta, field.getType(), field.getGenericType());
							var entity = entityProcessor.getEntity(innerMeta);
							var inputType = entity.getInputType(innerMeta, field.getAnnotations());
							fieldBuilder.type(inputType);
							graphInputType.field(fieldBuilder);
						}
					}
				} catch (RuntimeException e) {
					throw new RuntimeException("Failed to process method " + field, e);
				}
			}
		}

		@Override
		protected InputTypeBuilder resolve() {
			var fieldMappers = new ArrayList<RecordMapper>();

			for (var field : this.meta.getType().getDeclaredFields()) {
				try {
					if (field.isSynthetic()) {
						continue;
					}
					if (field.isAnnotationPresent(GraphQLIgnore.class)) {
						continue;
					}
					if (Modifier.isStatic(field.getModifiers())) {
						continue;
					} else {
						//getter type
						if (!field.isAnnotationPresent(InputIgnore.class)) {
							TypeMeta innerMeta = new TypeMeta(meta, field.getType(), field.getGenericType());
							var resolver = entityProcessor.getResolver(innerMeta);
							fieldMappers.add(new RecordMapper(field.getName(), field.getType(), resolver));
						}
					}
				} catch (RuntimeException e) {
					throw new RuntimeException("Failed to process method " + field, e);
				}
			}
			return new RecordFieldBuilder(meta.getType(), fieldMappers);
		}
	}
}